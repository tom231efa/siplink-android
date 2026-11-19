#!/usr/bin/env bash
# Builds and starts the NAT lab. Secrets are generated once into ./.env (git-ignored).
#
#   ./up.sh                          for an emulator on this host
#   LAN_ADDRESS=192.0.2.10 ./up.sh   also for a phone on the local network, published on that address
#
# The PBX runs the image of the test PBX (testenv/asterisk) under a name of the lab's own, so
# that rebuilding one environment does not change the other: it is taken from an image of the
# test environment that is already there, else built from testenv/asterisk.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  umask 077
  {
    echo "SIP_PASSWORD=$(openssl rand -hex 12)"
    echo "AMI_USER=natlab"
    echo "AMI_SECRET=$(openssl rand -hex 16)"
  } > .env
  echo "Generated testenv/natlab/.env"
fi

if ! docker image inspect siplink-natlab-asterisk:local > /dev/null 2>&1; then
  if docker image inspect siplink-testenv-asterisk:local > /dev/null 2>&1; then
    docker tag siplink-testenv-asterisk:local siplink-natlab-asterisk:local
  else
    docker build -t siplink-natlab-asterisk:local ../asterisk
  fi
fi

# With LAN_ADDRESS the lab is published on that address of this host (docker-compose.lan.yml).
files=(-f docker-compose.yml)
[ -n "${LAN_ADDRESS:-}" ] && files+=(-f docker-compose.lan.yml)
docker compose "${files[@]}" up -d --build "$@"
docker compose "${files[@]}" ps
