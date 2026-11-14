#!/usr/bin/env bash
# Stops and removes the test environment containers and network.
set -euo pipefail
cd "$(dirname "$0")"
docker compose down
