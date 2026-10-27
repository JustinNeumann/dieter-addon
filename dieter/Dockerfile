# Multi-arch HA base image (glibc, see below); the Supervisor's local build and CI both pass BUILD_ARCH.
ARG BUILD_FROM=ghcr.io/home-assistant/base-debian:trixie
FROM ${BUILD_FROM}
ARG BUILD_ARCH
# Dieter comes from the fork JustinNeumann/dieter (upstream 0.6.429 + the Antigravity harness), whose
# "Fork server release" workflow publishes the daemon tarballs with SHA256SUMS signed keyless. Verified:
#   cosign verify-blob --bundle SHA256SUMS.sigstore.json \
#     --certificate-identity https://github.com/JustinNeumann/dieter/.github/workflows/fork-server-release.yml@refs/tags/v${DIETER_VERSION} \
#     --certificate-oidc-issuer https://token.actions.githubusercontent.com SHA256SUMS
# The harness runtime *sources* (runner.mjs + adapters) are taken from the same tag's source tree and
# checked against RUNTIME_SOURCES_SHA (sha256 of the `sha256sum` lines of the LC_ALL=C-sorted files); the bridge lays
# them over the release's node_modules archive at start. Node checksums: nodejs.org/dist/vX/SHASUMS256.txt.
ARG DIETER_VERSION=0.6.429-antigravity.2
ARG DIETER_COMMIT=f65a42843da6eae2247d1d38eee981b6e6416ee2
ARG RUNTIME_SOURCES_SHA=d68763f51e2656dff84e6f3d5753183050cb23e0ccf4e3bfc559004efa16dbe2
ARG NODE_VERSION=22.23.4

# glibc base (musl breaks the OMP harness; trixie for Python 3.13, which hass-cli needs); git, tmux and
# python3 for the daemon and the bridge, openssh-client for Git over SSH and for reaching other machines
# with the add-on's key (/data/.ssh), python3-venv for hass-cli
RUN apt-get update \
    && apt-get install -y --no-install-recommends git tmux python3 python3-venv xz-utils procps openssh-client \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    case "${BUILD_ARCH}" in \
      aarch64) D=arm64; DSHA=64afc8f021c3f37c4206a509922479e7fe42a17774eb712763f831aead28b8b0; \
               N=arm64; NSHA=6287aa46b6c375d816e63ae66d024fbbfd70404825aabdfdf898a970871d2f4b ;; \
      amd64)   D=amd64; DSHA=ce5903831b73388da797dba37af94220840e2968ceb5c11500853147d6a0ca0b; \
               N=x64;   NSHA=1805bcd35e6815559bd2eb97db438d3fdb0eb3a5e6a977b7c1badca76337e9ce ;; \
      *) echo "unsupported BUILD_ARCH ${BUILD_ARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/node.tar.xz "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${N}.tar.xz"; \
    echo "${NSHA}  /tmp/node.tar.xz" | sha256sum -c -; \
    tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --exclude='*/CHANGELOG.md' --exclude='*/README.md'; \
    curl -fsSL -o /tmp/dieter.tar.gz "https://github.com/JustinNeumann/dieter/releases/download/v${DIETER_VERSION}/dieter-linux-${D}.tar.gz"; \
    echo "${DSHA}  /tmp/dieter.tar.gz" | sha256sum -c -; \
    mkdir /tmp/d && tar -xzf /tmp/dieter.tar.gz -C /tmp/d --strip-components=1; \
    install -m 755 /tmp/d/dieter /usr/local/bin/dieter; \
    install -m 755 /tmp/d/dieter-capture /usr/local/bin/dieter-capture; \
    rm -rf /tmp/node.tar.xz /tmp/dieter.tar.gz /tmp/d; \
    node --version && dieter --version

# Harness CLIs the Dieter workers call: Claude Code (pinned, sha256 from Anthropic's manifest),
# Codex (npm) and Google's Antigravity CLI (`agy`). HOME is /data at runtime, so their configs persist.
# Antigravity is pinned to one release: URL and SHA512 are what the installer's manifests
# (https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_{arm64,amd64}.json)
# listed on 2026-10-27; the installer (antigravity.google/cli/install.sh) would always take the latest.
# Its post-install step (`agy install`) only edits shell profiles for PATH and is not needed here.
ARG CLAUDE_VERSION=2.1.311
ARG CODEX_VERSION=0.163.0
ARG AGY_VERSION=1.4.8
ARG AGY_BUILD=5447777392656384
RUN set -eu; \
    case "${BUILD_ARCH}" in \
      aarch64) C=linux-arm64; CSHA=2e2496c9a8065df93acb0fca2d341f59a6b8777649b2e025ab8c9bcd5eef61f3; \
               A=linux-arm/cli_linux_arm64; ASHA=783ee9edd0f73e853af977e2848294ffb97b67b31ee19dccafb001f03f34b033371a2bb3371b61b78843c61bd4817ef1d2863a2d371b26513ea17d921c584bee ;; \
      amd64)   C=linux-x64;   CSHA=86dc1fcc69498159eaf15f446613e847010f9036bfbbdffd4c49350be8489ada; \
               A=linux-x64/cli_linux_x64;   ASHA=b23683771438e72bffe0a2231168f65a16a0d73ed2c7a8460b311dc5fe0c3c9d9dfc6cbfb7d8755173340071aa4cc5c7a552fb5d1be3fdd31c2b7559d67e404a ;; \
    esac; \
    curl -fsSL -o /usr/local/bin/claude "https://downloads.claude.ai/claude-code-releases/${CLAUDE_VERSION}/${C}/claude"; \
    echo "${CSHA}  /usr/local/bin/claude" | sha256sum -c -; chmod 755 /usr/local/bin/claude; \
    npm install -g --no-fund --no-audit "@openai/codex@${CODEX_VERSION}" && npm cache clean --force; \
    curl -fsSL -o /tmp/agy.tar.gz "https://storage.googleapis.com/antigravity-public/antigravity-cli/${AGY_VERSION}-${AGY_BUILD}/${A}.tar.gz"; \
    echo "${ASHA}  /tmp/agy.tar.gz" | sha512sum -c -; \
    tar -xzf /tmp/agy.tar.gz -C /tmp antigravity && install -m 755 /tmp/antigravity /usr/local/bin/agy; \
    rm -f /tmp/agy.tar.gz /tmp/antigravity; \
    claude --version && codex --version && agy --version

# hass-cli for the agents (REST API of HA core; the bridge sets HASS_SERVER to the Supervisor's core
# proxy and HASS_TOKEN to the add-on's token, homeassistant_api in config.yaml). PyPI, version-pinned.
# The Supervisor CLI `ha` is deliberately not included: it would need hassio_api and hand the agents
# add-on/host management.
ARG HASS_CLI_VERSION=1.0.0
RUN set -eu; \
    python3 -m venv /opt/hass-cli; \
    /opt/hass-cli/bin/pip install --no-cache-dir --disable-pip-version-check "homeassistant-cli==${HASS_CLI_VERSION}"; \
    ln -s /opt/hass-cli/bin/hass-cli /usr/local/bin/hass-cli; \
    hass-cli --version

# The daemon and its agents run as this user, not as root (Claude Code refuses
# --dangerously-skip-permissions as root); /data is handed to it at start.
RUN useradd --uid 1000 --user-group --home-dir /data --no-create-home --shell /bin/bash dieter

# Harness runtime sources of exactly this Dieter build (the fork's internal/harness/runtime without
# tests and node_modules); the bridge combines them with the release's node_modules archive.
RUN set -eu; \
    curl -fsSL -o /tmp/src.tar.gz "https://github.com/JustinNeumann/dieter/archive/${DIETER_COMMIT}.tar.gz"; \
    mkdir -p /tmp/src /opt/dieter-runtime; \
    tar -xzf /tmp/src.tar.gz -C /tmp/src --strip-components=1 --wildcards '*/internal/harness/runtime/*' 'dieter-*/LICENSE'; \
    cd /tmp/src/internal/harness/runtime; \
    find . -maxdepth 1 -type f \( -name '*.mjs' -o -name 'package.json' -o -name 'package-lock.json' \) ! -name '*.test.mjs' | LC_ALL=C sort | xargs sha256sum > /tmp/sources.sha; \
    echo "${RUNTIME_SOURCES_SHA}  /tmp/sources.sha" | sha256sum -c -; \
    cut -c67- /tmp/sources.sha | xargs -I{} cp {} /opt/dieter-runtime/; \
    cp /tmp/src/LICENSE /opt/dieter-runtime/LICENSE; \
    rm -rf /tmp/src /tmp/src.tar.gz /tmp/sources.sha; \
    ls /opt/dieter-runtime | wc -l

COPY bridge/ /opt/bridge/
COPY run.sh /run.sh
RUN chmod 755 /run.sh

CMD ["/run.sh"]
