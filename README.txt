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

Lightning swap receiving (2026-11-10):
  Lightning funds -> Receive has a top-right Swaps toggle, off by default.
  Off: ordinary XBT invoices, including optional amounts, using the embedded
  fork's compulsory XBT feature bit. On: enter the fixed XBT sats to receive;
  the controller supplies a BTC Lightning invoice for the sender to pay.
  Configure a saved controller's Wallet Receive API origin and private receive
  key (StartOS -> Swap Setup -> Wallet Receive Access). Tor origins use the
  app's embedded Tor client. This is a receive-only credential, not a node rune.
  Original invoice and request state persist before network dispatch. Resume
  via Swap requests; Retry request reuses that original invoice. Labels remain
  attached to the wallet's XBT invoice and appear in settled payment history.
  BTC QR validation checks network features, hash, amount and deadline. An
  expired payment window hides the QR but does not claim the swap failed until
  the controller reports a terminal outcome. Received XBT is verified locally.
  The description is retained on the XBT invoice; it is not copied to the BTC
  invoice. Swaps require a fixed amount; ordinary XBT receive does not.
  First API supports authenticated BTC-to-XBT receiving only. Automatic swap
  sending and public unauthenticated quote access remain future work.

  Validation: Android build/unit tests/lint; five focused Android parser and
  Tor transport tests; explicit live Tor API authentication/status probe.
  Controller tests cover durable retries, actual journal/worker integration,
  status without writes, wrong identity/network/credentials and secret omission.
  Mainnet wallet configured and updated, but funded phone receive is untested:
  this wallet needs a channel with inbound liquidity first. No payment sent.

Two-emulator desktop testing (2026-11-10):
  XBT:  AVD xbt-alpha, serial emulator-5554, existing funded test wallet.
  Zeus: AVD zeus-test, serial emulator-5556, fresh Android storage.
  Always specify adb -s emulator-5554 or -s emulator-5556; never assume the
  default device when operating on wallet data or installing an APK.
  XBT uses three virtual CPUs; Zeus uses two. Both request 2 GiB RAM,
  software graphics and no snapshots (Android may raise the RAM minimum).
  Persistent wallet data is retained. Local AVD refresh rates are back to 60 Hz.
  The attempted 30 Hz / two-core XBT tuning worsened reported freezes and was
  reverted. GNOME hang detection is back to its original 5000 ms timeout.
  Zeus is temporarily stopped to isolate XBT responsiveness under lighter load.
  Start: systemctl --user start zeus-emulator.service
  Stop:  systemctl --user stop zeus-emulator.service
  Desktop launcher: Zeus BTC Emulator
  Script: scripts/run-zeus-emulator-desktop.sh
  Installed official Zeus v13.2.4 x86_64, APK SHA256:
  94afbea4665267d3b079163d7590ce4ed7847d1cb9475395a3c83bf045d36ba9
  Verified signed release manifest (PGP fingerprint
  96C225207F2137E278C31CF7AAC48DE8AB8DEE84) and APK signing certificate against
  Zeus's published Android fingerprint. Release files are under .tooling/zeus.
  Both emulator windows are visible side by side on the Remmina desktop.
  Zeus is at its welcome screen; no Zeus wallet/seed or node connection created.

RBF payment replacement (2026-11-14)
------------------------------------
Advanced > RBF/CPFP lists eligible unconfirmed outgoing on-chain payments.
This first implementation performs RBF, not a CPFP fallback. It preserves all
inputs, recipient outputs, sequences and locktime, and reduces only an unspent
wallet-owned change output. It supports native P2WPKH/P2TR wallet inputs with
known confirmed parents; max sends without change, funding/splice transactions,
unknown inputs and insufficient change are excluded with an explanation.
Non-signalling transactions require full-RBF relay policy. The confirmation
shows the requested rate, total fee and added fee before signing/broadcast.

Embedded LND uses WalletKit FinalizePsbt/PublishTransaction; remote CLN uses
reserveinputs(exclusive=false), signpsbt and sendpsbt. Restricted remote runes
must permit these methods. The replacement is revalidated before signing and
submission is recorded before broadcast. An ambiguous response does not
silently authorize another submission. CLN originals superseded by a known
replacement or confirmed conflicting spend are excluded from selection.

Validation: ReplacementTransactionTest and ClnRemoteContractTest (14 tests),
plus real disposable XBT regtest LND and CLN replacement broadcasts. Both
originals were evicted, replacements confirmed, and recipient outputs preserved.
No mainnet funds or channels were used. Regtest harness/results are under the
ignored .tooling/rbf-regtest directory; optional device fixtures are isolated to
org.xbt.wallet.profiletest.

Channel backup export and testing (2026-11-14)
---------------------------------------------
Advanced contains Test seed recovery, Create channel backup, and Test channel
backup. Export uses Android's document picker; pending export files stay in
private app cache and are removed on completion/cancel. Seed/hsm_secret is not
included. Keep it separately and refresh channel backups after channel changes.

Embedded exports are native LND encrypted multi-channel .backup files. The
read-only test calls VerifyChanBackup and compares decrypted channel points
against a fresh export. Remote CLN exports are JSON containing format/version,
network, node_id, created_at, scb[], and a checksum. For CLN recovery tooling,
scb[] is the array returned by staticbackup; this is not emergency.recover's
binary format. Its test checks corruption/metadata and compares entries with
staticbackup from the connected node. The checksum is not authentication, and
mismatched older entries cannot be certified recoverable by this comparison.

Neither test invokes restoreChannelBackups/recoverchannel, closes channels,
or proves that peers will cooperate. Recovery import is not implemented in
this app. CLN node backups must also preserve the matching hsm_secret.

Validation: five Android file/coverage/export tests passed. A disposable XBT
regtest channel stayed open while LND exported/verified its real backup and
rejected a corrupted copy; CLN staticbackup entries matched across exports.

App PIN lock
------------
Advanced -> Add a PIN code configures an app-wide 6-12 digit PIN.
The inactivity timeout defaults to 15 minutes; separate dropdowns select
1–60 seconds, minutes, hours, or days. Restart-only disables inactivity locking
and greys out the timeout controls while retaining their selection. Background time counts;
a fresh app process always starts locked. The node keeps monitoring channels.
Change/remove requires the current PIN. Incorrect attempts are persistently
throttled after five failures. The stored verifier uses salted PBKDF2-HMAC-SHA256
(120,000 iterations), then an Android Keystore HMAC key; the PIN is not stored.
This is an app access lock, not additional encryption of the node wallet.
There is no forgotten-PIN reset: retain seed/channel backups or remote-node
connection information. In-app seed restoration remains backlog work.
