#!/usr/bin/with-contenv bashio
# Add-on entry point: the operator (Kotlin) stages the harness runtime and supervises the Dieter daemon; the web host
# serves Ingress on 8099 and holds the page's gateway session. Both run from one jar, as different users.
set -euo pipefail

export HOME=/data
export DIETER_HOME=/data/dieter
mkdir -p "${DIETER_HOME}" /data/projects
chmod 700 "${DIETER_HOME}"
export BRIDGE_LOG_LEVEL="$(bashio::config 'log_level' 'info')"

# A Dieter gateway on a name this container cannot resolve (e.g. a Tailscale host) gets a hosts entry:
# gateway_host_ip pins the host of gateway_url; the TLS certificate is still verified against the name.
# (read with jq: bashio::config asks the Supervisor for a key the options file lacks, which has no answer outside HA)
GW_URL="$(jq -r '.gateway_url // ""' /data/options.json 2>/dev/null || true)"; GW_IP="$(jq -r '.gateway_host_ip // ""' /data/options.json 2>/dev/null || true)"
if [ -n "${GW_URL}" ] && [ -n "${GW_IP}" ]; then
  GW_HOST="$(printf '%s' "${GW_URL}" | sed -E 's#^[a-z]+://##; s#[:/].*$##')"
  if [ -n "${GW_HOST}" ] && ! grep -q " ${GW_HOST}$" /etc/hosts; then
    echo "${GW_IP} ${GW_HOST}" >> /etc/hosts && bashio::log.info "gateway host ${GW_HOST} pinned to ${GW_IP}"
  fi
fi

# everything under /data belongs to the dieter user (once; later files are created by it)
if [ ! -f /data/.owned-by-dieter ]; then
  bashio::log.info "handing /data to the dieter user…"
  chown -R dieter:dieter /data && touch /data/.owned-by-dieter
fi
chown dieter:dieter /data 2>/dev/null || true
# The add-on's options: the operator reads a copy without the API key (it shares its user with the agents); the
# original, which the Supervisor rewrites at every start, stays root's. The key goes to the web host only, never
# into an exported variable (the operator's children, the daemon and the agents, inherit those).
# (/data is the agents' directory: remove whatever is at the copy's path, a link included, before root writes it;
# nothing of the agents runs yet)
rm -f /data/.operator-options.json
(umask 077; jq 'del(.api_key)' /data/options.json > /data/.operator-options.json 2>/dev/null || echo '{}' > /data/.operator-options.json)
chown dieter:dieter /data/.operator-options.json
[ -L /data/options.json ] || { chown root:root /data/options.json && chmod 600 /data/options.json; } 2>/dev/null || true
api_key="$(jq -r '.api_key // ""' /data/options.json 2>/dev/null || true)"
# The add-on's token (SUPERVISOR_TOKEN, HASSIO_TOKEN its old name) is an administrator's in Home Assistant. The web
# host (user dieter-web) gets it and relays the operator's events and titles (127.0.0.1:8097); the operator, and
# through it the daemon and the agents, get it only with the option agents_home_assistant (their hass-cli). s6 keeps
# the container's environment world-readable in /run/s6/container_environment: root's only.
chmod 700 /run/s6/container_environment 2>/dev/null || true
operator_env=(-u SUPERVISOR_TOKEN -u HASSIO_TOKEN)
[ "$(jq -r '.agents_home_assistant // false' /data/options.json 2>/dev/null)" = true ] && operator_env=()
ha_relay=""
[ -n "${SUPERVISOR_TOKEN:-}" ] && ha_relay="http://127.0.0.1:8097"
bashio::log.info "Dieter $(dieter --version) · node $(node --version) · Ingress on :${BRIDGE_PORT:-8099} · store ${DIETER_HOME} · user dieter"

# The operator: the add-on's API in Kotlin (same jar as the web host): the daemon, the board's state and its events
# stream, the cards, Dreamfall, the settings, on loopback (127.0.0.1:8096). It runs as the dieter user, the daemon's
# and the agents', and holds no secret of the add-on's own (not the API key; the Supervisor token only when the agents
# get Home Assistant). A daemon an exited operator left behind is stopped by the next one before it starts its own.
# The heap holds the board's state.
(
  while true; do
    setpriv --reuid=dieter --regid=dieter --init-groups env "${operator_env[@]}" HOME=/data USER=dieter LOGNAME=dieter \
      DIETER_OPTIONS_FILE=/data/.operator-options.json DIETER_OPERATOR_TRUSTED_UIDS="0,$(id -u dieter-web)" \
      DIETER_HA_RELAY_URL="${ha_relay}" \
      java -XX:+UseSerialGC -Xms16m -Xmx128m -Xss512k -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=24m \
        -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true \
        -cp /opt/web-host/dieter-web-host.jar com.getdieter.operator.MainKt || true
    bashio::log.warning "operator exited; restarting in 5 s"
    sleep 5
  done
) &

# The web host, the add-on's front door on the Ingress port: the page, its core with the gateway session and device
# key, and /api/, which it passes to the operator. It runs as its own user with a private state directory, so agents
# (user dieter) can neither read the session nor reach it. It answers Ingress (172.30.32.2) in full, Home Assistant
# (172.30.32.1) or a caller with the API key on Home Assistant's routes only, and nobody else: not other add-ons,
# not the agents on loopback. This loop keeps the add-on running.
install -d -o dieter-web -g dieter-web -m 700 /data/web-host
chown -R dieter-web:dieter-web /data/web-host
WEB_CALLBACK="$(jq -r '.web_sign_in_callback // ""' /data/options.json 2>/dev/null || true)"
while true; do
  setpriv --reuid=dieter-web --regid=dieter-web --init-groups \
    env -i PATH=/usr/local/bin:/usr/bin:/bin HOME=/data/web-host LANG=C.UTF-8 TZ="${TZ:-}" \
      DIETER_WEB_HOST_STATE=/data/web-host \
      BRIDGE_PORT="${BRIDGE_PORT:-8099}" BRIDGE_INGRESS_NETS="${BRIDGE_INGRESS_NETS:-}" \
      BRIDGE_CORE_NETS="${BRIDGE_CORE_NETS:-}" BRIDGE_API_KEY="${api_key}" \
      SUPERVISOR_TOKEN="${SUPERVISOR_TOKEN:-}" DIETER_HA_RELAY_ADDR=127.0.0.1:8097 \
      DIETER_WEB_SIGN_IN_CALLBACK="${WEB_CALLBACK}" \
      DIETER_WEB_DEVICE_NAME="Home Assistant (web)" \
      DIETER_WEB_HOST_VERBOSE="$([ "${BRIDGE_LOG_LEVEL}" = debug ] && echo 1 || echo 0)" \
    java -XX:+UseSerialGC -Xms16m -Xmx160m -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m \
      -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true -jar /opt/web-host/dieter-web-host.jar || true
  bashio::log.warning "web host exited; restarting in 5 s"
  sleep 5
done
