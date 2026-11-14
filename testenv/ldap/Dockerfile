# OpenLDAP directory for SipLink integration tests (Debian's slapd). Local use only.
FROM debian:trixie-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends slapd ldap-utils \
 && rm -rf /var/lib/apt/lists/*

COPY slapd.conf.template people.ldif entrypoint.sh /opt/ldap-test/
RUN chmod +x /opt/ldap-test/entrypoint.sh

EXPOSE 389
ENTRYPOINT ["/opt/ldap-test/entrypoint.sh"]
