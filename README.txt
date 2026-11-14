XBT Wallet — Android alpha
==========================

An Android wallet for XBT (Bitcoin Blake2b), with on-chain coin control,
Lightning, remote Core Lightning control, and BTC/XBT Lightning swaps.

This is the first alpha, v0.1.0-alpha.1. Use small test amounts. Wallet recovery
import is unfinished; exporting or testing a backup is not a recovery flow.
The app is not a BTC wallet: BTC Lightning interoperability uses a swap controller.

Getting started
---------------

Download the APK and SHA256SUMS from this repository's GitHub Releases page:
https://github.com/BitcoinMechanic/xbt-wallet/releases

Requires Android 8.0/API 26 or later. The release APK is the current debug-signed
mainnet testing build (org.xbt.wallet.mainnettest), not a production-signed
store release. It updates the existing mainnet test app when its signing key
matches. Do not uninstall a funded wallet to work around an installation error.
Keep the phone wallet repository private while alpha testing continues.

On first launch choose:
- Easy mode: the phone runs the embedded XBT/BLAKE2b LND fork and synchronizes
  through Neutrino. Initial synchronization takes time. Back up the seed, fund
  the wallet on chain, and open a channel to use Lightning.
- Remote Control: connect to an existing supported XBT Core Lightning node
  using its REST connection details/QR. The phone holds connection credentials;
  keys and channels remain on the node. This is not generic support for every
  LND/CLN server or an invitation to enter a Lightning peer URI as a REST URL.

Use a reachable HTTPS interface over Start Tunnel, VPN, or Tailscale, or an
onion interface over the embedded Tor client. Private StartOS HTTPS certificates
must be trusted; do not disable certificate checks. Tor can be slower.
The mainnet build uses XBT peer discovery and mempool.guide fee estimates.
Advanced -> Fiat configures display currency and the XBT price source.

StartOS requirements for swaps
-----------------------------

The supported self-hosted swap setup currently expects an existing StartOS
server. The phone does not install StartOS, run the controller, or fund/open
the controller's channels for you. Standalone Linux deployment is backlog work.

Install compatible versions of these packages on the server:
- XBT full node and XBT Core Lightning:
  https://github.com/BitcoinMechanic/xbt-cln-startos
- BTC/SHA256 full node and BTC Core Lightning:
  https://github.com/BitcoinMechanic/btc-cln-startos
- Swap Controller:
  https://github.com/BitcoinMechanic/swap-controller-startos

Use the swap-enabled CLN packages and their compatible controller build.
Stock marketplace CLN packages should not be assumed to provide the required
coordinator hooks. Follow each package's installation/update instructions.
A controller that you already trust and have access to can be on someone
else's StartOS server; normal non-swap XBT payments do not require a controller.

Prepare the server
------------------

1. Synchronize both full nodes and connect each CLN node to the correct chain.
2. Fund both CLN nodes on chain. Open channels and wait until they are usable
   and connected. Provide spendable and receivable Lightning liquidity for the
   directions you intend to use, and retain on-chain funds for safety/fee needs.
   Channel capacity or headline balance alone is not usable liquidity: channel
   reserves, HTLC limits, route fees and peer connectivity also matter.
3. Enable "Trust Local Swap Controller" on BOTH CLN nodes. This authorizes the
   controller's scoped swap access. Suitable grants are created and renewed
   automatically; routine swaps should not require manual grant approval.
4. Follow the controller's first-install initialization instructions. Leave
   Local Node Setup enabled and wait for the worker and Local Lightning Nodes
   health checks to report ready. Do not reinitialize existing journals to
   resolve a health error. Restored services may require explicit recovery.
5. In Interfaces, enable phone-reachable addresses for BOTH "Wallet Discovery"
   and "Wallet Swap API". Use the actual StartOS interface URLs, including
   assigned ports; do not guess URLs from internal container port numbers.
6. Under Swap Setup -> Wallet Discovery, enable publishing and configure the
   controller name, its XBT Lightning public key, and discovery endpoint(s).
   This public key belongs to the controller's XBT CLN node, not its BTC node
   or the phone's wallet.
7. Under Swap Setup -> Wallet Swap Access, enable access and obtain the private
   wallet API access key/QR. Keep it private. Configure the provider's pricing
   markup if desired, and review the resulting limits and fees.

Discovery and wallet access are different interfaces:
- Controller address = Wallet Discovery address. It provides read-only identity
  and endpoint metadata; it does not authorize creating swaps.
- Wallet Swap API address = authenticated quote/create/status/cancel interface.
  It uses the wallet access key, not your StartOS password or a CLN admin rune.
  The host may be the same, but ports or onion addresses can differ.

Connect the phone
-----------------

1. Use a separate XBT wallet/node from the controller's own XBT coordinator.
   The wallet may be embedded or remote-controlled; keep the two roles distinct.
2. Open the dots menu -> Swap controllers -> Add controller.
3. Enter or scan the Wallet Discovery address. Check the returned identity
   against your controller and save it.
4. Open the saved controller -> Configure wallet swap access. Enter or scan
   its Wallet Swap API address and wallet access key, then save.
5. Ensure the phone can reach those addresses and its own node is ready.
   There is no bundled public controller directory; add a known controller
   or configure a compatible discovery directory.

The phone's wallet also needs Lightning liquidity: inbound to receive XBT,
spendable outbound to send XBT. Receiving requires an online wallet/node.
A remote-control connection does not itself create any liquidity.

Receive BTC into your XBT wallet
-------------------------------

1. Lightning -> Receive -> enable Swaps.
2. Select the configured controller and enter a fixed XBT amount to receive.
   The currency selector converts fiat/XBT/sats locally; the target asset is XBT.
3. Press "Create invoice (swap)". The wallet creates its XBT recipient invoice,
   and the controller supplies a BTC Lightning invoice.
4. Give the displayed BTC invoice to the BTC sender. Pay it only once and
   before its expiry. The controller receives BTC and pays XBT to your wallet.
5. Follow the saved swap request until completion. The wallet verifies receipt.

With Swaps off, Receive creates an ordinary XBT invoice instead. Ordinary
receiving supports amountless invoices; swap receiving requires a fixed amount.
A pending receive swap is accessible through "Existing swap" and "Cancel swap".

Pay a BTC Lightning invoice using XBT
------------------------------------

1. Obtain a fresh, fixed-amount BTC invoice from the recipient. For private
   recipient channels, include usable route hints and keep that node online.
2. Lightning -> Send: paste or scan the BTC invoice.
3. Select/configure the controller if prompted. Review the quoted XBT cost,
   fees, market-rate comparison and expiry. A cost more than 5% above market triggers
   the wallet's additional warning.
4. Accept the quote and confirm payment of the controller's XBT invoice.
   The controller receives XBT and pays the original BTC recipient.
5. Follow the saved request to its terminal result. Normal operation requires
   no per-payment actions in StartOS.

Current outgoing swaps require an amount-bearing BTC invoice. An amountless
BTC invoice is not made swappable merely by entering an amount in the wallet.

Status, cancellation and troubleshooting
---------------------------------------

Use Swap requests to resume saved requests after a lost reply. A timeout is
not proof that a request or payment failed. Do not create another payment
just because a response was lost; inspect the existing request first.
Cancellation is only available where the controller can safely accept it.
"Cancelling" is not final: authority/HTLC cleanup can continue until expiry.
Clearing finished requests from the UI does not reverse a payment.

If a swap is refused, retain its diagnostic code/reference and inspect node
health, spendable/receivable liquidity, channel reserves, route hints, invoice
expiry and the controller's grant/fee/delay limits. A large XBT amount may
convert to a BTC amount below the incoming channel's safety threshold.
Do not erase journals, remove funded services or reset authority as a workaround.

The controller has bounded journal/history and concurrency limits. Automatic
renewal does not eliminate those limits; unattended long-term maintenance
remains work in progress. Recovery/deadline protection can force-close channels.
Keep node/controller backups and follow their recovery instructions.

Backups and app security
-----------------------

Advanced -> Backup/Recover groups seed backup, Test seed recovery, Create
channel backup and Test channel backup. Remote wallets must back up their
seed/node data on the remote node. Backup tests are non-destructive checks,
not a guarantee of full recovery. Channel recovery relies on peers and can
close channels; seed words alone do not restore live Lightning channels.

Advanced -> Add a PIN code enables an app-wide 6–12 digit access lock.
Default inactivity timeout: 15 minutes. Choose a number and seconds/minutes/
hours/days, or "Only require PIN on restart". There is no forgotten-PIN reset.
The node keeps monitoring while the UI is locked; this PIN is not additional
encryption of the node wallet. Secure your recovery information before enabling it.

Building and testing
--------------------

JDK 25, Go 1.27.3, Android SDK platform 37.1 / Build Tools 36.0.0,
NDK 27.2.12479018, protoc and the fork's protobuf tools are required.
The checksum-pinned wrapper supplies Gradle 9.6.0.

Clone https://github.com/paulscode/lightning-fork into .deps/lightning-fork
at aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56. Install gomobile/gobind
v0.0.0-20261011034528-197770444867, run the fork's "make mobile-rpc", then
scripts/build-node.sh. This applies the tracked patches and creates
app/libs/Lndmobile.aar. Generated native binaries and SDKs are not in Git.

  source scripts/env.sh
  ./gradlew -PmainnetTest testDebugUnitTest assembleDebug

APK: app/build/outputs/apk/debug/app-debug.apk
Omitting -PmainnetTest builds the separate regtest app org.xbt.wallet.alpha.
-PisolatedTest uses org.xbt.wallet.profiletest for disposable instrumentation.
Never run the entire connected test suite against a funded wallet: some tests
create wallets. BUILD.txt and docs/development-history.txt contain historical
checkpoints, not current end-user setup instructions.

Source components retain their licenses. Source Sans 3's OFL license is bundled
under app/src/main/assets/licenses. There is no new blanket license grant for
the application. Known work and recovery limitations are tracked in SCOPE.md.
