# Tools of the NAT lab: the NAT box (nftables + conntrack, run by "natlab ctl"), the STUN server
# and the user-space SIP ALG. Local use only: never publish this image.
FROM golang:1.27.3-trixie AS build
WORKDIR /src
COPY go.mod *.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /natlab .

FROM debian:trixie-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends nftables conntrack iproute2 tcpdump \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /natlab /usr/local/bin/natlab
ENTRYPOINT ["/usr/local/bin/natlab"]
