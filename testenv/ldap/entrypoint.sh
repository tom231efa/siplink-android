#!/bin/sh
# Builds a fresh directory from people.ldif on every start, then runs slapd in the foreground.
set -eu

: "${LDAP_ADMIN_PASSWORD:?LDAP_ADMIN_PASSWORD is required}"
: "${LDAP_READER_PASSWORD:?LDAP_READER_PASSWORD is required}"

work=/opt/ldap-test
rm -rf /var/lib/ldap-test /run/ldap-test
mkdir -p /var/lib/ldap-test /run/ldap-test

admin_hash=$(slappasswd -s "$LDAP_ADMIN_PASSWORD")
reader_hash=$(slappasswd -s "$LDAP_READER_PASSWORD")
sed "s|@ADMIN_HASH@|$admin_hash|" "$work/slapd.conf.template" > /run/ldap-test/slapd.conf

{
  cat "$work/people.ldif"
  printf '\ndn: cn=reader,dc=siplink,dc=test\nobjectClass: simpleSecurityObject\nobjectClass: organizationalRole\ncn: reader\nuserPassword: %s\n' "$reader_hash"
} > /run/ldap-test/bootstrap.ldif

slapadd -f /run/ldap-test/slapd.conf -l /run/ldap-test/bootstrap.ldif
exec slapd -f /run/ldap-test/slapd.conf -h "ldap://0.0.0.0:389/" -d 256
