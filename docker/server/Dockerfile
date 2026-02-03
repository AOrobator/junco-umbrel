FROM debian:bookworm-slim

ARG SPARROW_VERSION=2.3.1
ARG TARGETARCH

ENV SPARROW_VERSION=${SPARROW_VERSION}
ENV SPARROW_HOME=/data
ENV JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw"

RUN apt-get update \
  && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    tar \
    libasound2 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnss3 \
    libx11-6 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxrandr2 \
    libxrender1 \
    libxss1 \
    libxtst6 \
    libxi6 \
    libfontconfig1 \
    libfreetype6 \
  && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
  case "${TARGETARCH}" in \
    amd64) sparrow_arch="x86_64" ;; \
    arm64) sparrow_arch="aarch64" ;; \
    *) echo "Unsupported arch: ${TARGETARCH}"; exit 1 ;; \
  esac; \
  curl -L -o /tmp/sparrowserver.tar.gz "https://github.com/sparrowwallet/sparrow/releases/download/${SPARROW_VERSION}/sparrowserver-${SPARROW_VERSION}-${sparrow_arch}.tar.gz"; \
  mkdir -p /opt/sparrowserver; \
  tar -xzf /tmp/sparrowserver.tar.gz -C /opt/sparrowserver; \
  rm /tmp/sparrowserver.tar.gz; \
  chmod +x /opt/sparrowserver/Sparrow/bin/Sparrow

RUN useradd -r -u 1000 -m sparrow \
  && mkdir -p /data \
  && chown -R sparrow:sparrow /data /opt/sparrowserver

USER sparrow

VOLUME ["/data"]

ENTRYPOINT ["/opt/sparrowserver/Sparrow/bin/Sparrow", "-d", "/data"]
