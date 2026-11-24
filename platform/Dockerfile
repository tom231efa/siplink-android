# SipLink management platform: one static binary on Alpine (design §7.5).
# The build stage runs on the builder's own architecture and compiles for the image's: the
# binary is pure Go, so an image for another architecture needs no emulated compiler.
FROM --platform=$BUILDPLATFORM golang:1.27.4-alpine3.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
ARG TARGETOS
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build -trimpath -buildvcs=false -ldflags "-s -w -X main.version=${VERSION}" -o /out/siplink ./cmd/siplink

FROM alpine:3.24
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 10001 siplink && mkdir /data && chown siplink /data
COPY --from=build /out/siplink /usr/local/bin/
# Licences of the embedded font and icons (THIRD_PARTY.md).
COPY THIRD_PARTY.md third_party/ /licenses/
USER siplink
ENV SIPLINK_DATA_DIR=/data
VOLUME /data
EXPOSE 8080 1389
HEALTHCHECK --interval=30s --timeout=6s --start-period=10s CMD ["siplink", "health"]
ENTRYPOINT ["siplink"]
CMD ["serve"]
