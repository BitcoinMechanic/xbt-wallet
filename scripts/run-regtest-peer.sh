#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
# Disposable test-network peer only. Matches the local Knots activation height.
exec "$PROJECT_ROOT/.tooling/lnd" \
  --lnddir="$PROJECT_ROOT/.tooling/regtest-peer" \
  --bitcoin.active --bitcoin.regtest --bitcoin.blake2b-activation-height=1 \
  --bitcoin.node=neutrino --neutrino.connect=127.0.0.1:19444 \
  --listen=127.0.0.1:19735 --rpclisten=127.0.0.1:19009 --norest \
  --alias='XBT Alpha Test Peer' --noseedbackup --tlsdisableautofill
