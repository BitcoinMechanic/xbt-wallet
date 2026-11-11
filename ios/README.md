# XBT Wallet for iPhone — native preview

This is a separate SwiftUI port of the Java Android app. It uses the same pinned
Paul Lamb XBT/BLAKE2b LND fork and patches, not Bitcoin's unmodified LND.
It does not read, migrate or alter the funded Android wallets.

## Build

On a Mac with Xcode (iOS 16 SDK or newer), Go 1.27.3 and Homebrew:

```sh
brew install protobuf xcodegen
scripts/build-ios-node.sh
cd ios
swift ../scripts/render-ios-icon.swift XBTWallet/Resources/xbt_logo.png XBTWallet/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
xcodegen generate
open XBTWallet.xcodeproj
```

Alternatively, run `scripts/build-ios-app.sh` to produce an unsigned device
archive, or `scripts/build-ios-app.sh --simulator` for a simulator build.

Select the **XBTWallet** scheme and an iPhone simulator. For a physical iPhone,
select your Apple development team in Signing & Capabilities and a unique bundle
identifier if necessary. The team/certificate/provisioning profile are not stored
in this repository. TestFlight requires an Apple Developer Program membership
and an App Store Connect app record.

`.github/workflows/ios.yml` builds the engine and unsigned device app on macOS,
then compiles/runs unit, engine lifecycle and onboarding tests in an iPhone
simulator. An unsigned `.app` is **not installable on a normal iPhone**; it needs
signing in Xcode. A simulator `.app` is only for the simulator.

## Port status

Implemented for this first preview:

- Native XBT mainnet Neutrino node, DNS peers, mempool.guide fee estimates,
  keysend receiving, sync status and native aezeed creation.
- Device-only Keychain password/seed, backup-excluded node data, recovery-word
  display, export of the native static channel backup.
- On-chain address/QR generation (new address per visit), optional BIP21 amount,
  sending, selected inputs, sweep, editable fee rate and estimated cost review.
- XBT Lightning receive/pay, amountless invoices, explicit payment review,
  persistent operation journal, labels and payment history.
- Channels, liquidity bars, labels, cooperative close confirmation, channel
  creation, on-chain funds and labels.
- Camera permission handling, QR scan routing and explicit scanner dismissal.
- Global sats/XBT/USD units, exact decimal amount conversion and fresh Neoxa
  XBT/USDC rates. No Bitcoin/BTC price substitution.

Not ported yet: Tor; Remote Control/Start9 certificates; automatic swaps and swap
provider discovery; multiple wallets/deletion; seed import/recovery test UI;
non-USD fiat selection; full Android animation/modal parity; channel-open coin
control and exact funding-budget preview. UI paths that need these features
report their absence. A BTC invoice is rejected rather than paid as XBT.

This is not yet equivalent to the Android alpha. Mainnet transaction flows need
physical-device testing after signing. The CI tests do not spend funds or use
production wallet keys.

## iOS lifecycle and recovery

The embedded node runs while the app is active. iOS may suspend it in the
background; this is not Android's foreground service and does not provide an
always-online Lightning node. Reopening resumes the process or unlocks the
existing wallet after a cold launch. The OS can terminate the process, so all
mutating operations are journaled before dispatch and never auto-retried.
Unresolved operations remain visible in history. Lightning history reconciles
terminal node results; ambiguous on-chain operations require manual inspection. Clearing/deleting the app is not a troubleshooting step.

Recovery words alone do not recreate channels. Save the static channel backup
again after channel changes. Recovery requires the seed, the backup and peer
cooperation; it returns balances on chain rather than restoring live channels.
Do not import the same funded seed into two simultaneously active nodes.

## Native bridge

`Native/json_rpc.go` wraps generated protobuf RPCs with `jsonpb`. Decimal strings
preserve 64-bit amounts, byte fields use base64 and enum names stay explicit.
Swift never implements key derivation, signing or consensus. Callback timeouts do
not cancel a node-side payment: the UI explicitly reports uncertain outcomes.

`Native/dependencies.lock` pins source and generation tools. The build uses its
own `.deps/ios-lightning-fork` checkout, leaving the Android checkout untouched.
