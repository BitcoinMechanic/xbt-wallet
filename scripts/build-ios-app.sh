#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
[[ "$(uname -s)" == Darwin ]] || { echo 'The iPhone app requires macOS and Xcode. Use the iPhone build GitHub workflow from Linux.' >&2; exit 1; }
command -v xcodegen >/dev/null || { echo 'Install XcodeGen: brew install xcodegen' >&2; exit 1; }
[[ -d "$project_root/ios/Frameworks/Lndmobile.xcframework" ]] || "$project_root/scripts/build-ios-node.sh"
cd "$project_root/ios"
swift ../scripts/render-ios-icon.swift XBTWallet/Resources/Assets.xcassets/xbt_logo.imageset/xbt_logo.png XBTWallet/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
xcodegen generate
if [[ "${1:-}" == --simulator ]]; then
  xcodebuild -project XBTWallet.xcodeproj -scheme XBTWallet -configuration Debug \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath DerivedData CODE_SIGNING_ALLOWED=NO build
else
  xcodebuild -project XBTWallet.xcodeproj -scheme XBTWallet -configuration Release \
    -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath DerivedData \
    -archivePath XBTWallet.xcarchive CODE_SIGNING_ALLOWED=NO archive
  echo 'Unsigned archive: ios/XBTWallet.xcarchive. Select your Apple team in Xcode to sign for a device or distribution.'
fi
