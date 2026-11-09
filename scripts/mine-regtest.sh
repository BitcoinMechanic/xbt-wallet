#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
blocks="${1:-1}"
[[ "$blocks" =~ ^[1-9][0-9]*$ ]] || { echo 'Specify a positive number of blocks.' >&2; exit 1; }
export LD_LIBRARY_PATH="$PROJECT_ROOT/.tooling/native-libs/root/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
cli=(/home/claude-ubuntu/gpt/lightning-swap/build-results/pruned-xbt-20261107/bitcoin-cli "-datadir=$PROJECT_ROOT/.tooling/regtest" -regtest -rpcport=19443 -rpcwallet=alpha-miner)
address="$("${cli[@]}" getnewaddress)"
exec "${cli[@]}" generatetoaddress "$blocks" "$address"
