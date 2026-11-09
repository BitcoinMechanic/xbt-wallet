#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT/.deps/lightning-fork"
# Source is pinned to aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56 plus our transport and discovery patches.
for patch_file in "$PROJECT_ROOT/patches/lnd-tor-fee-transport.patch" "$PROJECT_ROOT/patches/lnd-dns-discovery.patch"; do
if ! git apply --reverse --check "$patch_file" 2>/dev/null; then
  git apply --check "$patch_file"
  git apply "$patch_file"
fi
done
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 \
  -tags='mobile routerrpc autopilotrpc chainrpc invoicesrpc neutrinorpc offersrpc peersrpc signrpc walletrpc watchtowerrpc' \
  -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' \
  -o "$PROJECT_ROOT/app/libs/Lndmobile.aar" ./mobile
