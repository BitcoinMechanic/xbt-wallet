#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
exec "$PROJECT_ROOT/.tooling/lncli" --lnddir="$PROJECT_ROOT/.tooling/regtest-peer" \
  --network=regtest --rpcserver=127.0.0.1:19009 "$@"
