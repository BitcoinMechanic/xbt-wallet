XBT Wallet — native Android alpha

Native Java Android wallet with an embedded XBT/BLAKE2b LND node,
on-chain coin control, Lightning payments/channels, and embedded Tor.
This is development software; full wallet/channel recovery is unfinished.

Project contents
- app/src: application, vendored protobuf definitions, unit/instrumentation tests
- scripts: native library build and local emulator/regtest helpers
- patches: modifications to the pinned LND fork
- SCOPE.md / ux.md: product requirements, implementation history, and backlog
- BUILD.txt: local build notes and verification checkpoints (historical sections
  describe earlier versions; later checkpoints supersede them)

Build prerequisites
JDK 25, Go 1.27.3, Android SDK platform 37.1 / Build Tools 36.0.0,
NDK 27.2.12479018, protoc and the fork's protobuf generation tools.
Gradle 9.6.0 is provided through the checksum-pinned wrapper.
Local scripts/env.sh supports JAVA_HOME and ANDROID_HOME overrides; its
.tooling defaults describe the development workstation, not bundled tools.

Embedded node
Clone https://github.com/paulscode/lightning-fork into .deps/lightning-fork
and check out aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56.
Install gomobile/gobind v0.0.0-20261011034528-197770444867.
Run the fork's `make mobile-rpc` to generate mobile RPC bindings.
Then run scripts/build-node.sh; it applies the tracked fee/Tor and DNS discovery patches and
creates app/libs/Lndmobile.aar. The generated offers RPC delta from the
original build is also preserved in patches for source provenance.
Native AARs, generated bindings, SDKs, node data, and secrets are not in Git.

Android
  source scripts/env.sh
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

Regtest package: org.xbt.wallet.alpha
Isolated mainnet package: build the same tasks with -PmainnetTest.
Mainnet testing defaults are currently specific to the development setup;
review Node connection settings before use. The two packages keep separate
wallets. APK output: app/build/outputs/apk/debug/app-debug.apk.

Do not run all connected instrumentation tests against an existing wallet:
NodeSmokeTest creates wallets. Use a disposable test environment, or select
the explicitly read-only TorTransportTest when appropriate.

Third-party source licenses remain with their components. This repository
contains no new blanket license grant for the application.
