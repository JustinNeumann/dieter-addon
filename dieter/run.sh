#!/usr/bin/with-contenv bashio
# Add-on entry point: the operator (Kotlin) stages the harness runtime and supervises the Dieter daemon, the bridge
# serves Ingress on 8099, the web host holds the page's gateway session.
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
bashio::log.info "Dieter $(dieter --version) · node $(node --version) · bridge on :8099 · store ${DIETER_HOME} · user dieter"

# The operator: the add-on's API in Kotlin (same jar as the web host, docs/PLAN.md Step 2): the daemon, the board's
# state and its events stream, the cards, Dreamfall, the settings. The bridge forwards /api/ to it. It runs as the
# dieter user like the bridge, on loopback, and holds no secret; started before the web host's secret exists. A daemon
# an exited operator left behind is stopped by the next one before it starts its own. The heap holds the board's state.
if [ -f /opt/web-host/dieter-web-host.jar ] && command -v java >/dev/null; then
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
fi

# The web host: the new page's core (JVM) with the gateway session and device key. It runs as its own user
# with a private state directory, so agents (user dieter) can neither read the session nor talk to it:
# it answers only requests with the per-boot secret, which it reads from stdin and the bridge from an
# inherited descriptor (fd 3, closed by the bridge before it starts anything).
if [ -f /opt/web-host/dieter-web-host.jar ] && command -v java >/dev/null; then
  install -d -o dieter-web -g dieter-web -m 700 /data/web-host
  chown -R dieter-web:dieter-web /data/web-host
  WEB_SECRET="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
  WEB_CALLBACK="$(jq -r '.web_sign_in_callback // ""' /data/options.json 2>/dev/null || true)"
  (
    while true; do
      printf '%s\n' "${WEB_SECRET}" | setpriv --reuid=dieter-web --regid=dieter-web --init-groups \
        env -i PATH=/usr/local/bin:/usr/bin:/bin HOME=/data/web-host LANG=C.UTF-8 \
          DIETER_WEB_HOST_STATE=/data/web-host DIETER_WEB_HOST_SECRET_FILE=- \
          DIETER_WEB_SIGN_IN_CALLBACK="${WEB_CALLBACK}" \
          DIETER_WEB_BRIDGE_URL="http://127.0.0.1:${BRIDGE_PORT:-8099}/api/" \
          DIETER_WEB_DEVICE_NAME="Home Assistant (web)" \
          DIETER_WEB_HOST_VERBOSE="$([ "${BRIDGE_LOG_LEVEL}" = debug ] && echo 1 || echo 0)" \
        java -XX:+UseSerialGC -Xms16m -Xmx160m -Xss512k -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=48m \
          -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true -jar /opt/web-host/dieter-web-host.jar || true
      bashio::log.warning "web host exited; restarting in 5 s"
      sleep 5
    done
  ) &
  exec 3<<<"${WEB_SECRET}"
  unset WEB_SECRET
  export DIETER_WEB_SECRET_FD=3
fi
exec setpriv --reuid=dieter --regid=dieter --init-groups env HOME=/data USER=dieter LOGNAME=dieter python3 -u /opt/bridge/dieter_bridge.py
