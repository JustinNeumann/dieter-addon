#!/usr/bin/with-contenv bashio
# Add-on entry point: the bridge starts and supervises the Dieter daemon itself.
set -euo pipefail

export HOME=/data
export DIETER_HOME=/data/dieter
mkdir -p "${DIETER_HOME}" /data/projects
chmod 700 "${DIETER_HOME}"
export BRIDGE_LOG_LEVEL="$(bashio::config 'log_level' 'info')"

# everything under /data belongs to the dieter user (once; later files are created by it)
if [ ! -f /data/.owned-by-dieter ]; then
  bashio::log.info "handing /data to the dieter user…"
  chown -R dieter:dieter /data && touch /data/.owned-by-dieter
fi
chown dieter:dieter /data /data/options.json 2>/dev/null || true   # the Supervisor rewrites options.json as root
bashio::log.info "Dieter $(dieter --version) · node $(node --version) · bridge on :8099 · store ${DIETER_HOME} · user dieter"
exec setpriv --reuid=dieter --regid=dieter --init-groups env HOME=/data USER=dieter LOGNAME=dieter python3 -u /opt/bridge/dieter_bridge.py
