# Name server of the SipLink test environment: resolves the *.siplink.test names of the PBX and
# the LDAP server (the names their TLS certificate is issued for) and forwards everything else.
# Local use only.
FROM debian:trixie-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends dnsmasq-base \
 && rm -rf /var/lib/apt/lists/*

EXPOSE 53/udp 53/tcp
ENTRYPOINT ["dnsmasq", "--keep-in-foreground", "--log-facility=-", "--no-hosts", "--bind-interfaces", "--user=root"]
