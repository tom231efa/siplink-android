# Asterisk PBX for SipLink integration tests. Local use only: never publish this image.
# Built from the official source tarball of the current LTS release.
FROM debian:trixie-slim AS build

ARG ASTERISK_VERSION=22.12.0
# From https://downloads.asterisk.org/pub/telephony/asterisk/asterisk-22-current.sha256
ARG ASTERISK_SHA256=f8b2ba98aac6c4ff3376bc0a2868bea6ed577ca684efb7ec739ae6e8ce6e35e2
ARG BUILD_JOBS=8

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential ca-certificates curl wget bzip2 patch pkg-config xmlstarlet \
      libedit-dev uuid-dev libxml2-dev libsqlite3-dev libssl-dev libsrtp2-dev \
      libjansson-dev libncurses-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src
RUN curl -fsSLO "https://downloads.asterisk.org/pub/telephony/asterisk/releases/asterisk-${ASTERISK_VERSION}.tar.gz" \
 && echo "${ASTERISK_SHA256}  asterisk-${ASTERISK_VERSION}.tar.gz" | sha256sum -c - \
 && tar xzf "asterisk-${ASTERISK_VERSION}.tar.gz" \
 && mv "asterisk-${ASTERISK_VERSION}" asterisk

WORKDIR /usr/src/asterisk
RUN ./configure --with-pjproject-bundled --with-jansson \
 && make menuselect.makeopts \
 && menuselect/menuselect --disable BUILD_NATIVE menuselect.makeopts \
 && make -j"${BUILD_JOBS}" \
 && make install DESTDIR=/out \
 # /var/run is a symlink to /run in the runtime image, so it cannot be copied as a directory.
 && rm -rf /out/var/run

FROM debian:trixie-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates libedit2 libuuid1 libxml2 libsqlite3-0 libssl3t64 libsrtp2-1 \
      libjansson4 libncurses6 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /out/ /
COPY config/ /etc/asterisk/
COPY phones/ /etc/asterisk-phones/
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && ldconfig

ENTRYPOINT ["/entrypoint.sh"]
