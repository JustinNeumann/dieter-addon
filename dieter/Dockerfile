# Multi-arch HA base image (glibc, see below); the Supervisor's local build and CI both pass BUILD_ARCH.
ARG BUILD_FROM=ghcr.io/home-assistant/base-debian:trixie
FROM ${BUILD_FROM}
ARG BUILD_ARCH
# Dieter comes from the fork JustinNeumann/dieter (upstream 0.6.431 + the Antigravity harness), whose
# "Fork server release" workflow publishes the daemon tarballs with SHA256SUMS signed keyless. Verified:
#   cosign verify-blob --bundle SHA256SUMS.sigstore.json \
#     --certificate-identity https://github.com/JustinNeumann/dieter/.github/workflows/fork-server-release.yml@refs/tags/v${DIETER_VERSION} \
#     --certificate-oidc-issuer https://token.actions.githubusercontent.com SHA256SUMS
# The harness runtime *sources* (runner.mjs + adapters) are taken from the same tag's source tree and
# checked against RUNTIME_SOURCES_SHA (sha256 of the `sha256sum` lines of the LC_ALL=C-sorted files); the operator lays
# them over the release's node_modules archive at start (its checksums: host/…/operator/Harness.kt). Node checksums: nodejs.org/dist/vX/SHASUMS256.txt.
ARG DIETER_VERSION=0.6.431-antigravity.8
ARG DIETER_COMMIT=2aafb54b68949a6474af4c0cef773b853243d41d
ARG RUNTIME_SOURCES_SHA=d68763f51e2656dff84e6f3d5753183050cb23e0ccf4e3bfc559004efa16dbe2
ARG NODE_VERSION=22.23.4

# glibc base (musl breaks the OMP harness; trixie for Python 3.13, which hass-cli needs); git, tmux and
# python3 for the daemon and the bridge, openssh-client for Git over SSH and for reaching other machines
# with the add-on's key (/data/.ssh), python3-venv for hass-cli; the GitHub CLI (below) for the daemon's
# pull-request operations and the agents
RUN apt-get update \
    && apt-get install -y --no-install-recommends git tmux python3 python3-venv xz-utils procps openssh-client \
    && rm -rf /var/lib/apt/lists/*

RUN set -eu; \
    case "${BUILD_ARCH}" in \
      aarch64) D=arm64; DSHA=7471c369bbfab5995abc85ea2a26dd69c30fb6c940159d11e01a0f92d7a92935; \
               N=arm64; NSHA=6287aa46b6c375d816e63ae66d024fbbfd70404825aabdfdf898a970871d2f4b ;; \
      amd64)   D=amd64; DSHA=995d4aaed76490ec77cbba98322bd23d03989b3f9f550ef319684ce5231405c7; \
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

# GitHub CLI: the daemon's pull-request operations (create_pr, refresh_pr, merge_pr) need `gh` on the host, and
# the agents read checks, Actions runs and pull requests with it. The bridge signs it in with the github_token
# option (HOME=/data) and lets git take the token from it. Release tarball, sha256 from the release's checksums.
ARG GH_VERSION=2.103.3
RUN set -eu; \
    case "${BUILD_ARCH}" in \
      aarch64) G=arm64; GSHA=ee32df2323c8847f5fac6cb6b4b69ca75828e9329a8f22f677021f7b3306ce63 ;; \
      amd64)   G=amd64; GSHA=4c806978ffce1cec70853302a56d70ac6da4f19bae7adca4e1a8ab00633dd91d ;; \
    esac; \
    curl -fsSL -o /tmp/gh.tar.gz "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${G}.tar.gz"; \
    echo "${GSHA}  /tmp/gh.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/gh.tar.gz -C /tmp "gh_${GH_VERSION}_linux_${G}/bin/gh"; \
    install -m 755 "/tmp/gh_${GH_VERSION}_linux_${G}/bin/gh" /usr/local/bin/gh; \
    rm -rf /tmp/gh.tar.gz "/tmp/gh_${GH_VERSION}_linux_${G}"; \
    gh --version

# vl-convert for the agents: Vega-Lite and Vega specs to SVG, PNG or PDF with bundled fonts and no browser, so an agent
# can look at a chart before it mails it (Dieter Mail draws vega-lite fences) or shows it in the chat. Release zip;
# sha256 = the release's asset digests. The zip is unpacked with Python (no unzip in the image).
ARG VL_CONVERT_VERSION=1.9.0
RUN set -eu; \
    case "${BUILD_ARCH}" in \
      aarch64) V=linux-aarch64; VSHA=99a5f96436efbb46fa469b88c28c9c9d864c02c283f18c38fd2b730eeb0ea320 ;; \
      amd64)   V=linux-64;      VSHA=f557637a159c7510a1486e1e41a86c3919abd71c18abc0826b499c76c712aa53 ;; \
    esac; \
    curl -fsSL -o /tmp/vlc.zip "https://github.com/vega/vl-convert/releases/download/v${VL_CONVERT_VERSION}/vl-convert_${V}.zip"; \
    echo "${VSHA}  /tmp/vlc.zip" | sha256sum -c -; \
    python3 -c "import zipfile; zipfile.ZipFile('/tmp/vlc.zip').extract('bin/vl-convert', '/tmp/vlc')"; \
    install -m 755 /tmp/vlc/bin/vl-convert /usr/local/bin/vl-convert; \
    rm -rf /tmp/vlc.zip /tmp/vlc; \
    vl-convert --version

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

# ha-logs: the add-on's own log reader for the agents (system log over the WebSocket API with filters; its
# shebang is the hass-cli venv's Python, which has aiohttp)
COPY tools/ha-logs /usr/local/bin/ha-logs
RUN chmod 755 /usr/local/bin/ha-logs && ha-logs --help >/dev/null

# The daemon and its agents run as this user, not as root (Claude Code refuses
# --dangerously-skip-permissions as root); /data is handed to it at start.
RUN useradd --uid 1000 --user-group --home-dir /data --no-create-home --shell /bin/bash dieter
# the web host's own user (see run.sh): agents run as dieter and cannot read /data/web-host
RUN useradd --uid 1001 --user-group --home-dir /data/web-host --no-create-home --shell /usr/sbin/nologin dieter-web

# Harness runtime sources of exactly this Dieter build (the fork's internal/harness/runtime without
# tests and node_modules); the operator combines them with the release's node_modules archive.
COPY patches/ /tmp/patches/
RUN set -eu; \
    curl -fsSL -o /tmp/src.tar.gz "https://github.com/JustinNeumann/dieter/archive/${DIETER_COMMIT}.tar.gz"; \
    mkdir -p /tmp/src /opt/dieter-runtime; \
    tar -xzf /tmp/src.tar.gz -C /tmp/src --strip-components=1 --wildcards '*/internal/harness/runtime/*' 'dieter-*/LICENSE'; \
    cd /tmp/src/internal/harness/runtime; \
    find . -maxdepth 1 -type f \( -name '*.mjs' -o -name 'package.json' -o -name 'package-lock.json' \) ! -name '*.test.mjs' | LC_ALL=C sort | xargs sha256sum > /tmp/sources.sha; \
    echo "${RUNTIME_SOURCES_SHA}  /tmp/sources.sha" | sha256sum -c -; \
    cut -c67- /tmp/sources.sha | xargs -I{} cp {} /opt/dieter-runtime/; \
    cp /tmp/src/LICENSE /opt/dieter-runtime/LICENSE; \
    python3 /tmp/patches/patch_runtime.py /opt/dieter-runtime; \
    rm -rf /tmp/patches; \
    rm -rf /tmp/src /tmp/src.tar.gz /tmp/sources.sha; \
    ls /opt/dieter-runtime | wc -l

# The page (Dieter's Compose UI in the browser, Kotlin/Wasm) and the web host (its core on the JVM), built from this
# repository (web/, host/, addon/) by tools/addon-dist.sh into web-dist/; the Builder workflow runs it before the image
# build. The same files for every architecture. The bridge serves the page at /next/ and relays next/core and
# next/signin to the web host, which run.sh starts as user dieter-web on loopback with Debian's headless Java 21.
# DIETER_WEB=mounted builds without them (tests/run.sh mounts a build over /opt/web and the jar).
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends openjdk-21-jre-headless; \
    rm -rf /var/lib/apt/lists/*; \
    java -version 2>&1 | head -1
ARG DIETER_WEB=built
COPY web-dist/ /tmp/web-dist/
RUN set -eu; \
    mkdir -p /opt/web /opt/web-host; \
    if [ -f /tmp/web-dist/dieter-web.tar.gz ] && [ -f /tmp/web-dist/dieter-web-host.jar ]; then \
      tar -xzf /tmp/web-dist/dieter-web.tar.gz -C /opt/web --strip-components=1 --no-same-owner; \
      cp /tmp/web-dist/dieter-web-host.jar /opt/web-host/dieter-web-host.jar; \
      test -f /opt/web/index.html && cat /opt/web/BUILD.json; \
    elif [ "${DIETER_WEB}" = mounted ]; then echo "page and web host left out (DIETER_WEB=mounted)"; \
    else echo "web-dist/ has no page or web host: run tools/addon-dist.sh first" >&2; exit 1; fi; \
    rm -rf /tmp/web-dist

# the fork release this image carries: the operator stages its harness runtime (harness-staged/<version>)
ENV DIETER_VERSION=${DIETER_VERSION}
COPY bridge/ /opt/bridge/
# the operator's helper scripts (the Claude quota probe for a setup-token login)
COPY operator/ /opt/operator/
# dreamfall: the command of the Dreamfall stage agents, a mode of the operator's jar (DreamfallCommand; they run it
# from their run cards). Small and short-lived like the operator; checked when the image carries the jar.
RUN printf '#!/bin/sh\nexec java -XX:+UseSerialGC -Xms8m -Xmx64m -XX:TieredStopAtLevel=1 -Xshare:auto -cp /opt/web-host/dieter-web-host.jar com.getdieter.operator.DreamfallCommand "$@"\n' \
      > /usr/local/bin/dreamfall && chmod 755 /usr/local/bin/dreamfall \
    && if [ -f /opt/web-host/dieter-web-host.jar ]; then dreamfall --help >/dev/null; fi
COPY run.sh /run.sh
RUN chmod 755 /run.sh

CMD ["/run.sh"]
