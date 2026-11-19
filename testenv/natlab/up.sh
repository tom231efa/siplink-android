#!/usr/bin/env bash
# Builds and starts the NAT lab. Secrets are generated once into ./.env (git-ignored).
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

docker compose up -d --build "$@"
docker compose ps
