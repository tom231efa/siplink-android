#!/usr/bin/env bash
# Creates a throw-away CA and one server certificate (Asterisk TLS + LDAP StartTLS) in ./certs.
# The directory is git-ignored; nothing generated here must ever be committed.
# LAN_ADDRESS and LAN_NAME (see up.sh) are added to the names the certificate is valid for.
set -euo pipefail
cd "$(dirname "$0")"

names="DNS:pbx.siplink.test,DNS:ldap.siplink.test,IP:172.31.250.10,IP:172.31.250.11"
[ -z "${LAN_ADDRESS:-}" ] || names="$names,IP:$LAN_ADDRESS"
[ -z "${LAN_NAME:-}" ] || names="$names,DNS:$LAN_NAME"
mkdir -p certs
cd certs
umask 077

openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt.pem -days 3650 \
  -subj "/O=SipLink Test/CN=SipLink Test CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/O=SipLink Test/CN=pbx.siplink.test" 2>/dev/null

openssl x509 -req -in server.csr -CA ca.crt.pem -CAkey ca.key -CAcreateserial \
  -out server.crt.pem -days 825 -extfile <(cat <<EXT
subjectAltName=$names
basicConstraints=CA:FALSE
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EXT
) 2>/dev/null

rm -f server.csr ca.crt.srl
# The containers run slapd / Asterisk as root, the bind mount is read-only.
chmod 644 ca.crt.pem server.crt.pem
chmod 600 ca.key server.key
echo "Test certificates written to $(pwd)"
