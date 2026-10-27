#!/usr/bin/with-contenv bashio
# Add-on entry point: the bridge starts and supervises the Dieter daemon itself.
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
exec setpriv --reuid=dieter --regid=dieter --init-groups env HOME=/data USER=dieter LOGNAME=dieter python3 -u /opt/bridge/dieter_bridge.py
