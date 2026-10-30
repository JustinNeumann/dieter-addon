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
chown dieter:dieter /data /data/options.json 2>/dev/null || true   # the Supervisor rewrites options.json as root
bashio::log.info "Dieter $(dieter --version) · node $(node --version) · Ingress on :${BRIDGE_PORT:-8099} · store ${DIETER_HOME} · user dieter"

# The operator: the add-on's API in Kotlin (same jar as the web host): the daemon, the board's state and its events
# stream, the cards, Dreamfall, the settings, on loopback (127.0.0.1:8096). It runs as the dieter user, the daemon's
# and the agents', and holds no secret. A daemon an exited operator left behind is stopped by the next one before it
# starts its own. The heap holds the board's state.
(
  while true; do
    setpriv --reuid=dieter --regid=dieter --init-groups env HOME=/data USER=dieter LOGNAME=dieter \
      java -XX:+UseSerialGC -Xms16m -Xmx128m -Xss512k -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=24m \
        -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true \
        -cp /opt/web-host/dieter-web-host.jar com.getdieter.operator.MainKt || true
    bashio::log.warning "operator exited; restarting in 5 s"
    sleep 5
  done
) &

# The web host, the add-on's front door on the Ingress port: the page, its core with the gateway session and device
# key, and /api/, which it passes to the operator. It runs as its own user with a private state directory, so agents
# (user dieter) can neither read the session nor reach it: the page's core answers only the Supervisor's network
# (Ingress), never loopback, where the agents are. This loop keeps the add-on running.
install -d -o dieter-web -g dieter-web -m 700 /data/web-host
chown -R dieter-web:dieter-web /data/web-host
WEB_CALLBACK="$(jq -r '.web_sign_in_callback // ""' /data/options.json 2>/dev/null || true)"
while true; do
  setpriv --reuid=dieter-web --regid=dieter-web --init-groups \
    env -i PATH=/usr/local/bin:/usr/bin:/bin HOME=/data/web-host LANG=C.UTF-8 TZ="${TZ:-}" \
      DIETER_WEB_HOST_STATE=/data/web-host \
      BRIDGE_PORT="${BRIDGE_PORT:-8099}" BRIDGE_INGRESS_NETS="${BRIDGE_INGRESS_NETS:-}" \
      DIETER_WEB_SIGN_IN_CALLBACK="${WEB_CALLBACK}" \
      DIETER_WEB_DEVICE_NAME="Home Assistant (web)" \
      DIETER_WEB_HOST_VERBOSE="$([ "${BRIDGE_LOG_LEVEL}" = debug ] && echo 1 || echo 0)" \
    java -XX:+UseSerialGC -Xms16m -Xmx160m -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m \
      -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true -jar /opt/web-host/dieter-web-host.jar || true
  bashio::log.warning "web host exited; restarting in 5 s"
  sleep 5
done
