#!/usr/bin/env bash
# Stops and removes the containers and networks of the NAT lab.
set -euo pipefail
cd "$(dirname "$0")"
docker compose down
