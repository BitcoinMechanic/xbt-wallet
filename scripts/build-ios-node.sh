#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Darwin ]]; then
  echo 'Building Lndmobile.xcframework requires macOS with Xcode selected by xcode-select.' >&2
  exit 1
fi
xcrun --sdk iphoneos --show-sdk-path >/dev/null
node_source="$project_root/.deps/ios-lightning-fork"
revision=aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56
if [[ ! -d "$node_source/.git" ]]; then
  git clone --filter=blob:none --no-checkout https://github.com/paulscode/lightning-fork.git "$node_source"
  git -C "$node_source" checkout --detach "$revision"
fi
[[ "$(git -C "$node_source" rev-parse HEAD)" == "$revision" ]] || { echo 'Unexpected iOS node source revision' >&2; exit 1; }
for name in lnd-tor-fee-transport lnd-dns-discovery lnd-pending-funding-startup lnd-profile-shutdown lnd-generated-offers-rpc; do
  patch="$project_root/patches/$name.patch"
  if ! git -C "$node_source" apply --reverse --check "$patch" 2>/dev/null; then
    git -C "$node_source" apply --check "$patch"
    git -C "$node_source" apply "$patch"
  fi
done
export PATH="$(go env GOPATH)/bin:$PATH"
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
go install github.com/lightninglabs/falafel@v0.9.2
go install golang.org/x/tools/cmd/goimports@v0.37.0
(cd "$node_source/mobile" && SUBSERVER_PREFIX=1 ./gen_bindings.sh v0.9.2)
mkdir -p "$project_root/ios/Frameworks"
cd "$node_source"
gomobile bind -target=ios,iossimulator -iosversion=16.0 \
  -tags='mobile routerrpc autopilotrpc chainrpc invoicesrpc neutrinorpc offersrpc peersrpc signrpc walletrpc watchtowerrpc' \
  -ldflags='-s -w' -o "$project_root/ios/Frameworks/Lndmobile.xcframework" ./mobile
