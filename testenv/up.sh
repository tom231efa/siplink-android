#!/usr/bin/env bash
# Builds and starts the test environment. Secrets are generated once into ./.env (git-ignored).
#
#   ./up.sh                                   for an emulator on this host
#   LAN_ADDRESS=192.0.2.10 ./up.sh            also published on that address of this host, for a
#                                             real device on the local network
#   LAN_ADDRESS=192.0.2.10 LAN_NAME=pbx.example ./up.sh
#                                             ... which knows this host under that DNS name
#
# See run-tests.sh for what the device needs.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  umask 077
  {
    echo "SIP_PASSWORD=$(openssl rand -hex 12)"
    echo "AMI_USER=siplink"
    echo "AMI_SECRET=$(openssl rand -hex 16)"
    echo "VM_PIN=$(shuf -i 1000-9999 -n 1)"
    echo "LDAP_ADMIN_PASSWORD=$(openssl rand -hex 12)"
    echo "LDAP_READER_PASSWORD=$(openssl rand -hex 12)"
  } > .env
  echo "Generated testenv/.env"
fi

# The certificate has to cover every address and name the devices connect to.
certificate_covers() {
  openssl x509 -in certs/server.crt.pem -noout -ext subjectAltName 2>/dev/null |
    tr ',' '\n' | sed 's/^ *//' | grep -qxF "$1"
}
compose=(docker compose -f docker-compose.yml)
recreate=()
if [ ! -f certs/server.crt.pem ] ||
  { [ -n "${LAN_ADDRESS:-}" ] && ! certificate_covers "IP Address:$LAN_ADDRESS"; } ||
  { [ -n "${LAN_NAME:-}" ] && ! certificate_covers "DNS:$LAN_NAME"; }; then
  ./gen-certs.sh
  recreate=(--force-recreate) # running containers still hold the old certificate
fi
[ -z "${LAN_ADDRESS:-}" ] || compose+=(-f docker-compose.lan.yml)

"${compose[@]}" up -d --build "${recreate[@]}" "$@"
"${compose[@]}" ps
