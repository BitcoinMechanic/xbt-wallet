# Lightning mobile app — living scope and specification

Status: brainstorming and product scoping. This file records confirmed requirements, proposed approaches, and unresolved decisions. It is not yet an implementation commitment or a complete release specification.

## Product intent

Build an XBT-first Lightning phone application with a user experience comparable to ZeusLN. XBT means the BLAKE2b chain in this project. Easy Mode runs a local XBT Lightning node and on-chain wallet, opens channels on that network, and uses swap providers to pay Lightning recipients on other chains. Discover providers automatically and allow users to add their own. Integrate the separately developed swap controller.

## Confirmed requirements

### First-run mode selection

- On launch, show mode selection if no mode has previously been selected (including first launch).
- Persist the selected mode and use it on subsequent launches without repeating selection.
- Easy Mode explains that it will start an embedded node before proceeding.
- Offer Easy Mode and Hard Mode. The later mode-switching flow remains to be specified.

### Easy Mode for beginners

- Offer an Easy Mode that starts and uses an embedded Lightning node on the phone. LND is the preferred initial approach, subject to swap and chain compatibility validation.
- Users do not need to own, configure, or connect to a remote Lightning node to use Easy Mode.
- Embedded operation is part of the intended scope, superseding the earlier remote-node-only recommendation. Evaluate Paul Lamb’s LND fork first; embedded CLN or another approach is a fallback if required capabilities cannot be delivered. No backend pivot has been selected.
- Include an on-chain XBT wallet with a deposit address so users can fund the app.
- Run on the BLAKE2b chain and establish Lightning channels with peers on that network, not SHA256 Bitcoin peers.
- Use XBT-compatible ecosystem infrastructure. Avoid public Electrum servers; the proposed default has no Electrum dependency.
- The onboarding and everyday payment experience should suit beginners. The first-channel flow and optional seed-backup prompt are specified below; remaining liquidity, backup, and recovery details are open. On-chain deposits and spendable Lightning balance must be distinguished.

### Easy Mode onboarding and first channel

User instructions: [ux.md](ux.md), reviewed 2026-11-08. The original notes are preserved unchanged.

- **Units:** “sats” always means XBT/BLAKE2b satoshis. BTC/SHA256 satoshis are called “Suits” or “suits”. Keep chain identity explicit in cross-chain displays; protocol field names need not change.
- **Backup:** after Easy Mode selection, offer seed-phrase backup. Allow “I'll do it later, I promise”. Verification, reminders, import, and channel recovery remain unresolved; do not imply that a seed alone restores every channel state.
- **Invitation:** ask “Ready to open your first Lightning channel?” Allow deferral.
- **Deposit:** after amount entry, show an XBT address and QR code; tapping the address copies it. Accepted copy: “Send sats to this address. Once your funds are ready, you can open your first channel.” Channel opening follows explicit peer selection and user authorization. Whether the initial amount means deposit target or desired channel capacity remains open.
- **Detection:** when the incoming unconfirmed transaction is detected, leave the address/QR screen and return home. Show the transaction with a Sparrow-style confirmation pie chart. Pre-confirmation detection is required UX with a backend dependency still to resolve.
- **Eligibility:** home shows “Open Channel”, disabled when no eligible UTXOs exist or confirmations are insufficient. Allow unconfirmed funding inputs where supported by the backend and applicable policy; otherwise wait for the required confirmations.
- **Peer:** tapping the available action asks “We need a peer to open a channel with — would you like to select one from our recommended list?” Alternatively allow manual entry, including QR scanning of peer connection details. The project will maintain an externally hosted list of recommended XBT Lightning peers.
- **Funds:** allow selection of the new UTXO and/or other eligible outputs, then channel size. Suggest retaining enough on-chain funds for reserves and enforce backend requirements where necessary.
- **Authorization:** the user presses “Open Channel” to submit. Return home and display channel-funding confirmation status, distinguishing pending funding from a usable channel.

### UX review: technical findings and proposed refinements

Reviewed 2026-11-08 against the previously pinned backend; no mobile or funded tests were run.

- **Unconfirmed inputs and zero-conf channels differ.** The fork exposes `min_confs` and `spend_unconfirmed` for funding inputs, and a separate `zero_conf` channel option. Funding from an unconfirmed output is supported at the API level; wallet eligibility needs testing. Zero-conf channel use requires compatible peer negotiation. Do not assume a universal confirmation count. [Fork RPC schema](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/lnrpc/lightning.proto)
- **Pending-deposit detection needs work.** Neutrino documents receipt notifications only after confirmation. The requested earlier transition needs an additional validated mechanism. Transitioning on confirmation is a proposed fallback, not an accepted replacement. [Neutrino rescan documentation](https://github.com/paulscode/neutrino-blake2b/blob/blake2b/README.md#rescan)
- **Coin selection and reserves:** the fork exposes funding `outpoints` and an anchor-reserve wallet balance. Calculate fees and applicable reserves through the backend. Distinguish on-chain reserves from channel reserve constraints affecting spendable channel balance. [Fork RPC schema](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/lnrpc/lightning.proto)

Proposals — not yet accepted:

- Label confirmation charts with actual counts and the relevant target, not elapsed-time percentages. Show deposit and channel-funding progress separately, and explain disabled actions.
- Before submission, show capacity, funding fee, and remaining on-chain balance/reserve. Revalidate eligibility after peer selection and when coins become spent, locked, or affected by a reorg.
- Keep the deposit address accessible from home, allow leaving the waiting screen, and persist opening attempts across restart to prevent duplicate submissions.
- Define directory hosting, identity validation, freshness, outage handling, and recommendation criteria. Lightning channel peers remain distinct from swap providers and chain-sync peers.

### Hard Mode — remote node control

- Ask the user to connect a remote Lightning node.
- Act as a control interface for that remote node rather than starting an embedded node for this mode.
- Supported remote node implementations, connection/authentication methods, and available controls remain to be specified.
- Whether Hard Mode includes the same swap-provider features as Easy Mode remains open.

### Multiple chains

- XBT/BLAKE2b is the primary local chain, wallet asset, and Lightning network.
- Let users pay Lightning recipients on other chains through swap providers, spending local XBT liquidity.
- Initial destination chains and swap directions beyond outbound XBT remain to be selected. The existing controller's BTC/XBT pair is a candidate, not a commitment to every other chain.
- This requirement does not require an embedded node or locally held channel balance for each destination chain. Additional local-chain wallets remain unconfirmed.
- Paul Lamb's BLAKE2b LND fork is the initial embedded backend candidate; CLN/other alternatives remain conditional on feasibility.

### Swap-provider discovery and manual configuration

- Automatically find swap providers so basic cross-chain payment works without users entering a provider.
- Let users manually add their own swap providers/controllers at any time.
- Provider discovery and manual configuration must coexist; neither requires users to run their own controller.
- Automatic discovery is a confirmed feature; its protocol, bootstrap source, ranking, and trust rules are not yet selected. Availability still depends on a reachable provider with the required route and liquidity.
- Integrate the existing, separately developed [BitcoinMechanic/swap-controller-startos](https://github.com/BitcoinMechanic/swap-controller-startos) project (confirmed by the user).
- Support configuration of multiple controllers; selection behavior and connection fields remain to be defined.
- The inspected controller coordinates BTC/XBT swaps through CLN coordinators and currently exposes no inbound API. A mobile integration contract remains to be designed; do not assume Paul Lamb’s bridge protocol applies.

### Scope maintenance

- Keep this file current as the user brainstorms.
- Record user decisions as confirmed requirements.
- Keep assistant recommendations distinct from accepted scope.
- Preserve unresolved questions instead of silently choosing product behavior.

## Proposed approaches — not yet accepted

- React Native and TypeScript for a shared mobile application, subject to embedded LND integration and platform requirements.
- Separate chain, Lightning, and swap-controller adapters.
- Explicit chain/network identification and separate asset balances.
- Controller connection testing and capability discovery before use.
- Swap flow: quote, fee/expiry review, authorization, progress, settlement, and protocol-specific claim/refund handling.
- Persist pending swap state and recovery material so work can resume after app termination or connectivity loss.
- OS-protected secret storage and explicit payment authorization.

## Easy Mode decisions to resolve

- **First use:** wallet creation/import, seed handling, verification/reminders after the optional backup prompt, and readiness/sync experience.
- **First payment:** validate the first-channel flow above; resolve pending-deposit detection, initial amount semantics, confirmation policy, and subsequent liquidity needs.
- **Liquidity services:** whether to use an LSP, which service(s), associated fees, and whether users can change providers.
- **Receiving:** expected behavior while the app is backgrounded, closed, or offline; do not promise always-on receiving before validating the design.
- **Recovery:** distinguish recovery of on-chain funds, Lightning channels, and swaps; define what must be backed up and how restoration works.
- **Mobile lifecycle:** startup, synchronization, battery/data usage, interrupted payments, and safe restart behavior.
- **Mode boundaries:** which settings beginners see and how users switch between Easy Mode and Hard Mode without creating ambiguity about their funds or leaving embedded-node channels unattended.

## Other open decisions

- Android, iOS, or both; launch order.
- Initial destination chains and mainnet/testnet rollout; the primary local asset/network is confirmed as XBT/BLAKE2b.
- Controller repository is confirmed; wallet-facing API, authentication, capabilities, and version compatibility remain unresolved.
- Controller trust model: who holds keys, signs transactions, and can recover funds during an outage.
- Supported swap directions and pairs; fees, quote expiry, settlement, refunds, and recovery responsibilities.
- Whether the app will be AGPL and reuse Zeus code, use separately negotiated rights, or be independently implemented.
- Which Zeus components are suitable for reuse after code and dependency review.
- Release boundaries and explicit exclusions. Earlier suggestions to defer features are proposals, not accepted exclusions.

## Zeus licensing research

Initial source review: 2026-11-08. This is a planning summary, not a complete dependency or distribution review.

- The Zeus license header grants AGPLv3-or-later rights. Covered code can be reused and modified under those terms, including commercially.
- Distribution of a derivative app entails AGPL obligations, including corresponding source, required notices, and modification notices. Modified versions supporting remote network interaction have an additional source-offer requirement.
- PP Neue Montreal and Marlide Display fonts are explicitly excluded from the grant and may not be reused outside Zeus. Replace them in any derivative application.
- The license lists `legal@zeusln.com` for discussion of separate licensing; no alternative permission has been obtained.
- Use independent app branding and inspect third-party dependencies/assets before copying them.
- A separate controller service is not automatically covered merely because the app calls its API; the actual integration and licensing boundary require review.

Sources:

- [Zeus LICENSE](https://github.com/ZeusLN/zeus/blob/master/LICENSE)
- [Zeus README](https://github.com/ZeusLN/zeus/blob/master/README.md)
- Repository head observed during initial research: `eb27332f0e54b81a8404f2e07f2111a6585f2217`. Review the exact revision selected for reuse before importing code.

## Embedded backend and swap feasibility research

Reviewed 2026-11-08: [paulscode/lightning-fork](https://github.com/paulscode/lightning-fork), branch `blake2b`, commit `aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56`. Findings below come from source/document inspection, not a successful mobile build or an executed swap test.

### What the fork provides

- It targets Bitcoin BLAKE2b-chain Lightning and deliberately separates it from SHA256-chain Lightning. It is not a generic multi-chain LND runtime.
- Its optional `bridgerpc` service implements Lightning-to-Lightning swaps between those two chains. The documented mechanism links an incoming hold invoice and destination invoice using the same payment hash; settlement uses the destination payment’s preimage. This is not evidence of arbitrary on-chain swap support.
- Bridge operators run the fork plus a stock SHA256 LND with liquidity on both sides. A wallet consuming that bridge need not operate the bridge or its second node.
- The repository retains Android AAR and iOS XCFramework build targets and mobile bindings. Build targets are evidence of an embedding path, not proof this revision works on either phone platform.
- The root license is MIT; preserve its copyright and permission notice. This does not remove AGPL obligations if the app incorporates Zeus code. Dependency review remains outstanding.

### Existing payer protocol worth evaluating

The fork’s bridge documentation points to [lightning-fork-bridge’s payer API](https://github.com/paulscode/lightning-fork-bridge/blob/master/docs/payer-api.md). This related project was inspected as an alternative integration. The user subsequently identified `BitcoinMechanic/swap-controller-startos` as the actual controller; `lfbridge:` and this payer API are **not** its established protocol.

- A manual `lfbridge:` code carries a version, label, endpoint, node public key, participant macaroon, and certificate fingerprint. Treat the code as a secret. Non-onion endpoints require certificate pinning; onion endpoints require an actual Tor transport.
- Participant REST operations are `GET /v2/bridge/info`, `POST /v2/bridge/quote`, and `POST /v2/bridge/swap`. Keep operator/admin credentials out of the payer flow.
- Before paying, validate the hold invoice against the original payment hash, expected bridge identity for that direction, chain feature bits, authorized amount, and expiry. Include the payer’s routing fee in the user’s total authorization.
- Use the local node’s payment result as the authority for payment completion/refund; bridge status is supplementary. Persist reconciliation state and validate any reported preimage against the payment hash.
- The payer guide focuses on `toSHA256`; reverse-direction identity and validation rules must be checked in implementation before claiming both directions work.
- Documentation differs on whether an unpaid quote reserves liquidity. Do not promise reservation semantics until tested against the pinned controller version.

### Constraints affecting Easy Mode

1. **One embedded instance:** `mobile/bindings.go` explicitly guards a singleton node. Concurrent local balances/channels on both chains are not established by the existing wrapper. Packaging stock LND and the fork together needs a separate runtime/linking/lifecycle feasibility investigation; simply calling Start twice does not work.
2. **Light-client infrastructure:** the fork replaces Neutrino with a BLAKE2b-compatible fork. Its docs require appropriate compact-filter peers and a chain-specific fee source on mainnet. Default DNS discovery is unsuitable for reliably finding this chain. Easy Mode must supply working infrastructure without requiring users to configure their own remote Lightning node.
3. **Chain identification:** the chain-identity document uses feature bits to distinguish invoices, including `option_blake2b` at 512/513; invoice prefixes and the shared genesis hash are insufficient identifiers. Verify against pinned code rather than relying on older README wording.
4. **Mobile bridge calls:** native RPC generation and build tags need verification if running the bridge inside the app. Calling an external controller’s REST API does not require embedding its bridge service.
5. **Recovery and background operation:** existing server bridge support does not prove safe phone suspension, restart, channel recovery, or swap reconciliation.

### Recommended feasibility gate — not yet executed

- Confirm target chains and the actual controller’s mobile protocol (repository now identified below), and whether users must hold local Lightning channels on both chains concurrently or only pay across chains through an external controller.
- Build and start the fork’s mobile library on each target OS; demonstrate sync, wallet creation, channel funding, and normal payment using the intended light-client configuration.
- Connect to a manually added controller and complete a cross-chain payment, verifying quote and hold-invoice checks locally.
- Exercise quote expiry, payment failure, controller outage, app termination/restart, duplicate requests, and settlement reconciliation. Check that uncertain payments cannot trigger duplicate spending.
- If simultaneous local nodes are required, prove runtime isolation and recovery before accepting that architecture.
- Keep LND if these gates pass. Evaluate embedded CLN or another implementation if a required gate fails; CLN is a fallback candidate, not yet researched or validated, and would need the same chain/protocol/mobile checks.

Research conclusion: this fork is a credible first candidate for BLAKE2b Lightning and its bridge protocol. Its necessity depends on which chain the phone’s node runs on and what the existing controllers require. The evidence does not justify a CLN pivot yet.

Source references at the inspected revision:

- [Bridge operation](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/docs/bridge.md)
- [Bridge RPC schema](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/lnrpc/bridgerpc/bridge.proto)
- [Chain/light-client requirements](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/docs/blake2b.md)
- [Chain identity](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/docs/blake2b-chain-identity.md)
- [Mobile binding](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/mobile/bindings.go)
- [Build targets](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/Makefile)
- [MIT license](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/LICENSE)

## Confirmed swap-controller integration

User-provided repository: [BitcoinMechanic/swap-controller-startos](https://github.com/BitcoinMechanic/swap-controller-startos).

Related development repository: [bitcoinmechanic/lightning](https://github.com/bitcoinmechanic/lightning). The user identified this as another repository where the swap project is being developed. Track it alongside the StartOS controller when assessing swap capabilities and backend requirements. Its relevant branches, changes, and relationship to the packaged controller have not yet been inspected.

Inspected on 2026-11-08 at commit `a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6` (`main`). This review read documentation, StartOS interfaces/actions, and selected controller code; it did not run builds, funded tests, or live swaps.

### Observed architecture

- This is a StartOS service package with Python controller logic and TypeScript StartOS actions, coordinating BTC and XBT CLN nodes. The repository uses XBT for the BLAKE2b asset; the pricing API calls it BTCB2. These are controller identifiers; app display units are now sats for XBT and suits for BTC.
- The package explicitly has no inbound API, web UI, or additional Lightning node. `startos/interfaces.ts` returns an empty interface list. Current user operations invoke controller scripts through StartOS actions.
- Coordinator pairing uses verified HTTPS, expected node identities, dedicated read-only runes, and CA certificates. Swap execution uses separately paired bounded grants. Read-only pairing is not payment authority.
- The normal workflow is recipient invoice → prepare/review → explicit confirmation → publish source-chain invoice → pay once → monitor/reconcile. BTC→XBT and XBT→BTC workflows exist.
- The documentation reports customer payments using LND, including a reverse receipt in LND. This is reported interoperability evidence, not a test performed during this review or proof of compatibility with every embedded LND version.
- The root license is MIT, with notice preservation required. Dependencies and any coordinator code imported separately still need their own review.

### Consequences for the phone app

- **Do not require embedded CLN solely because the coordinators use CLN.** The phone can potentially remain an LND payer/recipient while the server-side coordinators retain their CLN hooks, execution authority, and recovery worker.
- Paul Lamb’s fork remains a candidate for running BLAKE2b Lightning on the phone. Its `bridgerpc` API is a separate protocol and is not an established dependency of this controller.
- **Manual controller connections need a new integration surface.** An endpoint field alone cannot connect to the current package. Proposed work is a versioned, wallet-facing API on the controller or a companion gateway; this is not yet implemented or approved as a final design.
- Keep coordinator credentials and grant administration on the controller side in that proposed design. Give the phone participant-scoped authorization for its own quotes, confirmations, and status; do not expose StartOS administration or broad coordinator runes as the mobile API.
- The controller owns the durable swap execution/recovery state; the app should retain the controller identity, swap ID, reviewed terms, source invoice, and local payment identity needed to reconcile after restart. A missing response must not trigger a fresh payment or attempt.
- The existing state machine and authority boundaries should be preserved by the API, including review binding, expiry, uncertain outcomes, and cancellation only of unapproved drafts. Multi-user access, concurrency limits, request deduplication, and per-user status visibility require explicit design before sharing a controller with multiple wallets.

### Current controller constraints to account for

These describe the reviewed implementation, not permanent product requirements or verified production readiness:

- Recipient invoices must be whole-satoshi amounts with at least 33 minutes remaining and final CLTV at most 40; private route hints may be needed.
- Market quotes expire two minutes after price acquisition. Approval/payment must fit that window; accepted swaps retain their original terms during recovery and are not repriced.
- The documented flow supports one payment part and one outgoing attempt. An uncertain result must be reconciled rather than retried as a new payment.
- Market grants have per-swap and cumulative amount caps, slot budgets, and expiry; failed/expired approved swaps do not replenish the reserved budget. Readiness does not guarantee an available route.
- The latest README labels market pricing as a candidate and explicitly does not claim its funded/live validation until the required runs complete. Older user-reported successful swaps do not validate every newer change.
- Controller backup/restore intentionally excludes reusable execution authority and blocks restored execution. Mobile recovery must not accidentally bypass this barrier.
- On-chain recovery is a distinct state requiring claim/sweep verification, not a successful-payment label.

### Proposed next specification work

1. Decide whether the app is a customer of an operator’s controller, the operator’s own wallet, or both; define participant permissions accordingly.
2. Design controller connection import (address, authenticated identity, transport trust, participant credential) and version/capability negotiation. Do not assume `lfbridge:` is compatible.
3. Define prepare/review/confirm/status/cancel-draft operations around the existing state machine, with durable request identifiers and exact reviewed-term binding.
4. With XBT now confirmed as the primary chain, validate Paul’s embedded fork as an XBT payer/recipient against the actual CLN coordinators first, including chain feature compatibility, invoice decoding, routing, CLTV limits, and recovery. Stock BTC LND can be a remote test recipient; it is not the default app backend.
5. Decide whether simultaneous local Lightning channels on both chains are necessary. Only then choose single-runtime, isolated runtimes, or a different embedded implementation.

Sources at the reviewed revision:

- [User workflow and API absence](https://github.com/BitcoinMechanic/swap-controller-startos/blob/a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6/instructions.md)
- [Empty network interfaces](https://github.com/BitcoinMechanic/swap-controller-startos/blob/a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6/startos/interfaces.ts)
- [Forward prepare/confirm actions](https://github.com/BitcoinMechanic/swap-controller-startos/blob/a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6/startos/actions/repeatSwaps.ts)
- [Technical reference and validation history](https://github.com/BitcoinMechanic/swap-controller-startos/blob/a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6/README.md)
- [License](https://github.com/BitcoinMechanic/swap-controller-startos/blob/a4f74742e1ef1b4cf1db4c18077bb7414bcc1eb6/LICENSE)

## Proposed on-chain synchronization without Electrum

Research on 2026-11-08 confirms that the inspected Lightning Fork supports `bitcoin.node=neutrino` and replaces upstream Neutrino and btcwallet with BLAKE2b-compatible forks. This is the preferred feasibility path for Easy Mode; phone builds and deposit scanning have not yet been tested here.

### How it would work

1. The embedded wallet derives XBT keys and deposit addresses locally.
2. Its Neutrino backend connects to XBT full-node peers serving compact block filters, synchronizes headers, and checks BLAKE2b proof of work and fork identity.
3. It scans compact filters locally for wallet scripts and fetches matching blocks to identify relevant confirmed transactions. It does not need to send an address list or xpub to an Electrum indexer. Block requests can still reveal information; this is not perfect privacy.
4. The wallet tracks confirmed funds and supplies transactions for channel funding. New-wallet scan start and restored-wallet rescan history must be handled separately; restoring an older seed must not scan only from installation time.
5. On-chain deposits become Lightning spending capacity only after the required channel funding/opening process; define confirmations and readiness explicitly.

### Infrastructure and limitations

- Need reachable BLAKE2b full nodes configured with `blockfilterindex=1` and `peerblockfilters=1`. No Electrum address index is required, but this does not eliminate network infrastructure.
- The fork's docs say existing default DNS seeds mostly find SHA256 peers. Provide a maintained XBT bootstrap peer set and validate chain identity; allow advanced users to override it. Independent operators and redundant peers are proposed to reduce single-operator dependence. Peer diversity alone is not full validation.
- In the inspected implementation, `neutrino.connect` is the documented way to avoid unsuitable default discovery. Verify failover and peer selection on the exact mobile build.
- Need an XBT-specific fee estimation source: the fork documents `fee.url` for Neutrino on mainnet. Do not accidentally use a SHA256 Bitcoin estimator. Define estimator outage/stale-result behavior before release.
- Compact-filter scans detect confirmed deposits. Do not promise immediate visibility of externally sent unconfirmed payments: the Neutrino README documents that rescan receipt notifications arrive on confirmation. Any pending-deposit feature needs separate implementation evidence.
- Neutrino is a light client: the fork documents header/PoW validation, not full transaction/script consensus validation. A user-owned XBT full-node backend can be an advanced option; full local validation would require a much heavier mobile design.
- Test first sync duration, storage, mobile suspension/catch-up, deposit detection, reorgs, seed restoration, peer outages, and incorrect-chain rejection on actual target devices.

Sources:

- [Fork backend requirements](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/docs/blake2b.md)
- [Pinned wallet/Neutrino dependencies](https://github.com/paulscode/lightning-fork/blob/aa3f4a4e7c1fbad1b291b5acfe83e2b362d2fd56/go.mod)
- [Neutrino BLAKE2b mechanism and rescan behavior](https://github.com/paulscode/neutrino-blake2b/blob/blake2b/README.md)

## Proposed provider discovery design

The user confirmed automatic discovery; the following mechanism is a proposal rather than an existing controller capability:

- Ship bootstrap discovery sources that list provider identities, endpoints, protocol versions, and advertised chain pairs. Support updates and multiple independent sources; the specific discovery transport remains open.
- Query providers directly for current capabilities, availability, limits, and quotes. Discovery listings are leads, not authority to spend or proof that a provider is safe.
- Apply the same identity, transport, quote, invoice, and payment-hash checks to discovered and manually added providers. Custom providers can work without appearing in a default directory if they implement the supported protocol.
- Review source amount, destination amount, swap charges, local routing budget, and expiry before payment. Ranking and whether to use a preferred provider or compare several remain open.
- Do not silently switch provider or initiate another payment while an earlier payment outcome is uncertain.
- The current StartOS controller exposes no inbound API or established public discovery protocol. Building a participant-facing API and discovery metadata is prerequisite work; finding a controller address alone will not make it usable.
- Keep swap providers, XBT chain-sync peers, fee estimators, and Lightning channel peers distinct in the architecture. One operator may supply several roles, but discovering a swap provider does not establish a funded Lightning route to it.

## Decision log

| Date | Decision |
| --- | --- |
| 2026-11-08 | Maintain the scope/spec in this project throughout brainstorming. |
| 2026-11-08 | Include beginner Easy Mode with embedded LND; remote-node setup is not required. |
| 2026-11-08 | Investigate Paul Lamb’s LND fork for swap support; retain embedded CLN/other approaches as contingent fallbacks. |
| 2026-11-08 | Confirmed `BitcoinMechanic/swap-controller-startos` as the controller integration target; Paul’s bridge API remains a separate alternative. |
| 2026-11-08 | Added `bitcoinmechanic/lightning` as another user-identified swap development repository to review. |
| 2026-11-08 | Confirmed first-run/unselected mode prompt, persisted mode choice, and Easy Mode explanation of embedded startup. |
| 2026-11-08 | Confirmed XBT/BLAKE2b as the primary local on-chain wallet and Lightning network, with deposits funding local channels. |
| 2026-11-08 | Confirmed automatic swap-provider discovery plus optional manual providers for outbound cross-chain Lightning payments. |
| 2026-11-08 | Recorded strong preference to avoid public Electrum; proposed BLAKE2b Neutrino compact-filter synchronization. |
| 2026-11-08 | Confirmed Hard Mode asks for a remote Lightning node and acts as its control interface. |
| 2026-11-08 | Incorporated ux.md: optional seed backup, deposit-to-home flow, confirmation chart, channel eligibility, hosted peer list/manual QR entry, UTXO selection, capacity/reserves, and manual channel submission. |
| 2026-11-08 | Confirmed sats means XBT and suits means BTC satoshis. Pending-deposit detection remains unresolved; the deposit copy conflict was subsequently resolved by accepting the revised message. |
| 2026-11-08 | Accepted revised deposit copy: “Send sats to this address. Once your funds are ready, you can open your first channel.” Opening remains explicitly authorized by the user. |

## Android alpha implementation checkpoint — 2026-11-08

The user selected Android first and authorized starting the alpha. Work resumed after an interrupted session. Native Java/Android is the feasibility implementation; this does not settle the longer-term cross-platform stack.

- Implemented a foreground embedded XBT node service, device-keystore encrypted seed/password storage, persisted Easy Mode selection, optional seed display, deposit address/QR/copy, balances and transaction confirmations, confirmed-UTXO selection, manual/QR Lightning peer entry, explicit channel submission, and XBT invoice review/payment with bounded routing fees.
- Built the pinned fork as an Android AAR for ARM64 and x86_64. The router RPC build tag is required. Empty Go protobuf responses must be normalized to empty Java byte arrays, including the initial wallet-state response.
- The UI currently exposes **regtest only**, using an isolated XBT Knots compact-filter peer. No real funds or StartOS credentials are involved.
- This is an incomplete alpha, not completion of the confirmed product scope. Pre-confirmation deposit detection, deposit-target amount/automatic transition, recommended peers, unconfirmed coin selection, complete fee/reserve review, channel recovery, remote mode and swaps remain outstanding. Channel and payment UI paths require end-to-end validation.
- Build commands, exact tool versions, test setup and current limitations are recorded in `BUILD.txt`. Generated APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Validation completed: APK assembly, three policy unit tests, Android lint, and an Android 15 x86_64 emulator test covering embedded startup, wallet creation/encrypted seed persistence, server readiness and regtest deposit address generation. Neutrino logs showed compact-filter/header synchronization to the isolated peer at height 101. No physical-phone, live-funds, channel-opening or payment-settlement validation is claimed.
- Manual emulator validation additionally confirmed first-run Easy Mode, deferred backup, deposit QR/address generation, detection of a 1,000,000-sat regtest deposit after one confirmation, and persisted-wallet unlock/balance display after app restart. Home showed block 103 and enabled channel opening. This used valueless local regtest coins only.
- First-channel onboarding is permanently dismissed once this wallet has ever had a channel, including pending channels. Closing/removing the last channel must not restore the first-channel wording. Persist a one-way history marker and recover it from the node's existing or closed-channel history when upgrading older installations.

### Home and wallet navigation — accepted 2026-11-08

- Home shows two tappable summaries: total on-chain balance and count of current channels. No transaction feed, individual channel rows, local Lightning balance, or first-channel invitation on Home.
- Tap balance to inspect confirmed/unconfirmed UTXOs and save/edit labels keyed by transaction outpoint. Labels persist locally and appear in funding coin selection.
- Tap channel count to see open channels (including offline peers) and pending channels. Pending closures remain visible until resolved; completed closures do not count on Home.
- Closed-channel history is under More → Advanced → Closed-channel history. Payment, backup and other secondary actions move under More; receive is in the balance view and channel creation is in Channels.
- Open-channel rows are tappable. Channel details include capacity, balances, peer and funding outpoint, with an explicit Close Channel review/confirmation. The alpha requests cooperative closure, requires an online peer, accepts a closing fee rate, persists submissions to prevent duplicates, and reports pending/confirmed status. Force-close is not exposed by this action.
- Channels support editable local labels, persisted by funding outpoint (separate channels to the same peer have separate labels). Open-channel details offer Add/Edit label; pending-channel rows offer a label editor. Labels appear in channel lists and are retained in closed-channel history. Saving an empty label removes it.
- Channel list rows omit peer identities. Peer and funding-output details are shown only after tapping an open or pending channel. Pending-channel details also provide the label editor.
- Channel list rows show label/status plus local and remote balances, not capacity. Capacity and pending-confirmation details remain in the individual channel detail view.
- Receive address/QR automatically returns Home after a newly detected positive incoming transaction to the displayed address. Existing transactions are baselined when the screen opens so revisiting an already-funded address does not immediately dismiss it. Polls every five seconds while visible; no confirmation requirement is imposed by the UI. Current Neutrino backend generally reports incoming deposits only after confirmation; earlier detection remains a backend limitation.
- Channel capacity now offers “Use all selected funds”. It sends fund_max=true, local_funding_amount=0 and the explicit selected outpoints to the fork. The backend subtracts funding fees, enforces its reserve policy/channel limits and never expands selection to other outputs. Review discloses that exact capacity is determined on submission. This does not guarantee an extra on-chain cushion: the pinned fork treats private-channel anchor reserves differently and may require no reserve. Manual capacity remains available to retain additional funds.
- “Use all selected funds” displays the numeric sum of selected UTXOs in the capacity field, updating as selection changes. Supporting copy explains that this displayed total is before fees/reserves; fund_max still determines final capacity. Turning the option off restores the prior manual entry.
- Funding field is now “Funds to use”: a total budget inclusive of estimated miner fees and any on-chain reserve shortfall. Public channel checkbox defaults off (private). The form and review show estimated network fee, reserve retained from this budget, and the resulting channel capacity, replacing the generic warning.
- The preview preserves existing backend-reported anchor reserves and adds the pinned fork's 10,000-sat public-anchor requirement up to 100,000 sats total; unallocated wallet funds can cover that reserve. Both paths request ANCHORS. Fees conservatively account for selected input scripts and funding/change outputs, including the fork fee floor; node coin selection can produce a lower actual miner fee. The displayed capacity is sent explicitly and terms/output eligibility are rechecked before submission. This supersedes the earlier direct fund_max submission; “Use all” fills the total selected budget.
- Build, lint, and funding-preview tests cover private/public fee deductions, reserve coverage from other funds, existing reserves on private opens, reserve cap, and overspending rejection.
- Funding form/review show the reserve line only with Public channel selected. Private funding still preserves existing required reserves internally.
- Home shows the backend-reported total required on-chain anchor reserve only while any public channel is open or pending (including closing). It refreshes from WalletBalance rather than maintaining a potentially stale UI counter; completed closed channels do not keep the display visible.
- Payment history combines on-chain wallet transactions (including deposits and channel-funding movements), outgoing Lightning payments including incomplete/failed statuses, and settled incoming Lightning invoices. Entries are newest-first with dates and confirmations/status. It no longer reports an empty history merely because there have been no outgoing Lightning payments.

### Easy Mode infrastructure boundary — confirmed 2026-11-08

- Easy Mode must not depend on users owning, connecting, or configuring a full node, including for unconfirmed-deposit detection. The current manual regtest peer entry is a development harness, not the intended Easy Mode onboarding.
- Infrastructure connections/bootstrap must be automatic. An implementation requiring each user to provide their own XBT full node for mempool monitoring is rejected for Easy Mode.
- Unconfirmed-deposit detection still requires implementation. Candidate approaches, not selected: wallet-local matching of transaction announcements from automatically discovered XBT P2P peers (bandwidth/relay/availability validation needed), or an automatically configured app-operated mempool service (privacy, trust, outages and replacement/drop handling to be designed). Neither implies trusting unconfirmed receipts as settled funds.
- Main screen now has Send (Lightning invoice payment) and Receive (current on-chain address flow) buttons. Payment entry is removed from the overflow menu. The former channel-count button shows inbound (remote) and outbound (local) funds across open channels and still opens Channels. Pending channel funds are excluded. Channels displays the total current open/pending channel count at the top.

- Home balance buttons read “On chain: N sats” and “Channel funds: N sats” (local funds across open channels). Tapping either animates both balances smaller to the right and reveals the shared Send/Receive actions on the left. Tapping a smaller balance opens UTXOs or Channels. Expansion survives periodic balance refreshes. Existing Send (Lightning) and Receive (on-chain) flows are retained.

- Home interaction supersedes the shrinking layout: tapping On chain toggles a horizontal Send/Receive row immediately underneath, with a 260ms eased height/fade animation. Repeated taps reverse the animation; periodic balance refresh preserves expansion. Channel funds opens Channels directly. UTXO viewing/labels remain accessible through Wallet menu → On-chain outputs.

- Both home balances independently toggle animated Send/Receive dropdowns. On-chain Send reviews a node-validated address, amount and estimated fee before SendCoins with confirmed inputs; Receive uses the deposit address. Channel Send pays a Lightning invoice; Receive creates a one-hour fixed-amount private-route-hint invoice with QR/copy and returns home on settlement. Channels and UTXO details remain accessible in the wallet menu.
- On-chain Send lists confirmed, labelled UTXOs with explicit selection. Transactions pass only selected outpoints to SendCoins; empty selection and amounts exceeding the selection plus fees are rejected. Selected availability and preview terms are rechecked before submission.
- Manual/QR amounts specify the recipient output exactly; fees are paid in addition. Editable fee rate is in sat/vbyte. Live and review summaries show recipient amount, estimated network fee and combined total. Fees conservatively model selected script types, recipient/change outputs, dust change and the fork's fee floor; final fees can differ with node coin selection/signature sizes.
- Max enables Use entire selection, disables the amount editor, and previews selected value minus estimated fees (and any backend-required reserve). Submission uses send_all with only the selected outpoints. Turning it off restores the manual amount.
- Address scans/pastes accept plain addresses and bitcoin:/xbt: payment URIs. URI amount uses exact decimal coin-to-satoshi conversion (up to eight decimal places), switches off max, and populates the recipient amount. Duplicate parameters, unsupported required parameters and invalid amounts are rejected. Recipient checksum/network is checked by a node dry run before review.
- Unit tests cover URI conversion/rejection, fixed recipient amounts with additive fees, max, reserves, dust change, and insufficient selected funds. Build and lint pass.

- On-chain Send removes the redundant Max button. Use entire selection is available only after an address is entered; clearing the address or changing any UTXO checkbox clears it and restores the editable manual amount. Network fee rate (sat/vbyte) is a permanent label above the input, which still defaults to 1.

- Correction: Use entire selection is always available, even without a recipient address, and immediately disables the amount editor. Clearing the address does not clear the checkbox; changing any UTXO still clears it. Fee preview waits for a usable address.

- Use entire selection is disabled while no UTXOs are selected and enabled when at least one is selected, regardless of whether an address has been entered. Any UTXO selection change still clears it.

- Use entire selection now populates the recipient amount before an address is entered, subtracting a conservative fee estimate using a 43-byte recipient output. The preview updates for the actual destination type when entered. Review still requires an address.

- Removed the “Use entire selection; final recipient amount depends on the network fee” sentence from on-chain payment review.

- Selecting all listed available UTXOs automatically checks Use entire selection and populates/disables the amount. Deselecting any clears it; empty selection still disables it. Users can manually turn it off to enter a fixed amount.

- Reverted automatic Use entire selection when all UTXOs are checked. Any UTXO checkbox change clears Use entire selection; users explicitly enable it, and it remains disabled for an empty selection.

- Home balance dropdowns are mutually exclusive: expanding On chain smoothly collapses Channel funds and vice versa. Tapping the expanded balance still collapses it.

- On-chain Receive accepts an optional amount in sats. QR and Copy payment request update to a bitcoin: URI with an exact decimal amount; blank amounts use the plain address. The displayed address remains separately copyable. Invalid amounts hide the QR and disable request copying to prevent stale requests. Existing received-funds detection remains active.
- Home rows split into a left On chain / Channel funds button (mutually exclusive action dropdowns) and a right amount button. Each amount independently cycles sats → XBT → selected fiat, with unit preferences persisted. USD is the default; the dots menu offers 30 fiat currencies.
- Fiat uses Neoxa's XBT market, identified by the exchange as BTCB2_USDC: https://neoxa.exchange/api/exchange/ticker/BTCB2_USDC . USDC is treated as approximately USD; other currencies use https://api.frankfurter.dev/v1/latest?base=USD reference rates. Source/assumption text is in Advanced. No wallet addresses or balances are sent to either provider.
- Rates fetch in a separate thread only while fiat is displayed, at most once per minute; successful quotes are cached. Market quotes older than 15 minutes are not displayed; FX reference dates older than 7 days are rejected. Unavailable prices display a dash, not a fabricated zero. Fiat display is approximate; sat/XBT arithmetic uses exact decimal conversion.

- Renamed the home Channel funds button to Lightning funds.

- Viewing the seed requires a secure warning screen and explicit I understand · Show seed confirmation. Warning states anyone knowing the seed has access to funds, and clarifies seed-only recovery cannot restore Lightning channels; channel-fund recovery needs separate backups/peer assistance and is not implemented in this alpha. Cancel returns home without reading/displaying seed material.
- Seed display lists words numbered from 1. Wallet menu includes Test recovery: a non-destructive check of every numbered word against the local vault. Inputs are masked, excluded from autofill/saved view state, and request no personalized keyboard learning. The screen uses FLAG_SECURE. Success explicitly verifies the backup only, not a full wallet restore or Lightning recovery; no wallet data is reset.

- Fiat currency picker includes Off. When off, amount buttons cycle only sats/XBT; any currently fiat balance resets to sats. Preference persists and no new rate requests are triggered while fiat is disabled.
- Seed-generation review (2026-11-08): NodeService.createWallet calls embedded LND GenSeed with an empty request, letting walletunlocker use Go crypto/rand to generate 16 bytes of entropy and aezeed to encode 24 words. No custom mnemonic generator or caller-supplied entropy. Zeus's embedded-LND createLndWallet likewise calls lndMobile.wallet.genSeed. The pinned fork aezeed/cipherseed.go is byte-for-byte identical to ZeusLN/lnd master fetched during this review (SHA256 3b50ef5e48b07355ee13a85c24ddab6966844739920f389016f7be0f1f7ddf20). Backend go test ./aezeed ./walletunlocker passed. This establishes the generation path/core file match, not a security audit of the entire fork or app. Existing wallets are unchanged.
- Optional private labels are available for on-chain Send, on-chain Receive, and Lightning Send (120 characters). On-chain sends store the label through SendCoins; Lightning sends persist it by payment hash before submission. Both appear in payment history.
- On-chain receive labels persist with the current address and a pre-existing-transaction baseline. Newly detected incoming transactions and their wallet-owned outputs receive local labels without overwriting existing transaction/UTXO labels. Reconciliation also runs on Home/history so labels can apply after leaving the receive screen or restarting. Labels are not embedded in receive QR codes or Lightning invoices.
- Lightning Receive's Description is passed as Invoice.memo; the pinned backend uses zpay32.Description(invoice.Memo) when no description hash is set. Thus it is readable by anyone holding the invoice, including the sender; it is not a private wallet label.

- Lightning Receive explicitly labels Description as sender-visible and adds a private Label for personal records, stored locally by invoice hash and shown in payment history. On-chain Send places Label immediately below Amount; on-chain Receive places it below the displayed address.

- Lightning Receive amount is optional. Blank sends Invoice.value=0, causing LND to omit the amount in the generated invoice; the invoice screen says Sender chooses the amount. Entered values still require a positive whole number of sats. Description, private label, expiry and receipt detection are unchanged.

- Payment history promotes private labels to the top of each entry for all four send/receive flows, with Lightning invoice descriptions separately identified. On-chain sends additionally persist their label by returned txid; receive history can recover labels already attached to wallet UTXOs when no transaction label exists.

- Payment history has ALL, Lightning and On Chain tabs with immediate filtering of loaded data. On Chain includes every wallet transaction, including channel funding/closing; Lightning includes outgoing payments and settled incoming invoices. Labels, newest-first order and selected tab are preserved on refresh.

- Every payment-history entry is tappable to add/edit/clear its private label, using stable transaction/payment/invoice identifiers. Edits refresh the current tab immediately and survive reloads; empty overrides prevent deleted labels resurfacing from backend defaults. On-chain outputs is renamed On chain funds in menu and screen, with an explicit tap-to-edit hint for existing UTXO label editing.
- Visual refresh: charcoal background with mint accents, compact XBT wordmark/network badge, consistent rounded buttons with ripple/disabled states, outlined fields with visible focus/disabled styling, calmer typography, density-aware spacing and improved touch targets. Home balance pairs retain their separate tap actions and mutually exclusive animated dropdowns. History tabs have a clear selected state and history entries remain editable cards. Secure seed-screen handling remains unchanged.

### Backlog

- Verify runtime-version consistency between controller and node packages. Correction to the 2026-11-09 unpaid-expiry diagnosis: the earlier claimed week-long retirement delay was based on the controller image's bundled bounded_authority implementation, not the newer authority actually running on the Lightning nodes. The live BTC-to-XBT test moved from waiting_for_retirement to terminal Expired after its 17:31:50 UTC payment deadline, despite the recipient invoice remaining valid until November 16. Node retirement revokes an unused enrollment durably, refuses any prior send intent, checks payment/HTLC absence, and does not refund grant budget. The swap reached terminal Expired, but StartOS subsequently showed that the controller restarted at 17:32:37.949 UTC, 47 seconds after the payment deadline. No assistant-issued restart occurred during the expiry checks. The exact restart trigger and whether cleanup required it remain unverified; do not count this as a proven no-restart expiry test. A subsequent same-direction swap has not yet been tested. Do not treat the old week-long-wait claim as a reproduced bug.

- Remove the swap coordinator's 64-record ceiling as a barrier to unattended operation. Each node currently retains separate capped collections of grants, grant reviews and swap enrollments; expired grants and completed/retired enrollments still count. Automatic incoming/outgoing grant renewal can exhaust grant history in roughly a month even without swaps, sooner with activity. Implement safe archival/compaction with restart and crash recovery tests, preserving outstanding recovery, replay protection, pause/revocation decisions and accounting. There is no finished maintenance procedure today: do not delete journals or reset authority as a workaround. Required before unattended production use; initial small-scale testing can proceed.

- Allow users to choose their own oracles for fiat-price conversion and XBT network-fee estimation, with independently configurable sources and custom endpoints.

- Proper error messaging when invoices fail: explain invoice creation, validation and payment failures in plain language, identify actionable causes (such as expiry, wrong network, insufficient funds/liquidity, or no route), and give an appropriate next step. Distinguish a confirmed failure from a pending/unknown payment outcome so users are not encouraged to pay twice. Keep technical details available separately for troubleshooting.

### Tor transport / isolated mainnet test — 2026-11-08

- User-provided XBT mainnet compact-filter peer: oamf2472ggqj6srxyboxxnuvsdgrlyqjx22ieeurxqommu2zohjbyzyd.onion:8333. This is P2P, not RPC or a Lightning peer.
- Embedded Guardian Project tor-android 0.4.9.14, bound to app-private lifecycle with loopback-only SOCKS and HTTP CONNECT listeners. Three-minute bootstrap deadline with progress; node peer connections use LND's Tor transport and stream isolation. Connection tests use unresolved onion hosts through SOCKS, never local DNS/direct fallback.
- Advanced → Node connection supports Tor selection, peer settings, Tor bootstrap test and onion-port reachability test. Changes apply after app restart. Existing network/seed remain unchanged.
- Fixed a bypass in the fork's HTTP fee estimator: it now uses the configured node dialer, including Tor. Patch is tracked at patches/lnd-tor-fee-transport.patch and applied by scripts/build-node.sh. Tests verify use of that dialer and no direct fallback on proxy failure. Fiat requests also use embedded Tor's HTTP tunnel when Tor is enabled.
- Separate mainnet test package org.xbt.wallet.mainnettest (build with -PmainnetTest) preserves org.xbt.wallet.alpha's regtest data. Defaults to this onion peer and Tor. It does not start the wallet/node without an XBT-specific fee-estimator URL; no BTC estimator is substituted. Mainnet transaction forms/full readiness have not been validated.
- Verified embedded Tor bootstrap and a read-only instrumentation test connecting to the user's onion P2P port (TorTransportTest, 1 test passed). This proves transport reachability, not completed chain sync or successful Lightning operation. Existing backend fee tests and Java unit tests/build/lint passed. Native AAR rebuilt with fee transport patch.
- Dependency required toolchain update: JDK 25, Gradle 9.6.0 (distribution checksum pinned), AGP 9.4.0, compileSdk 37.1; targetSdk remains 35 and minSdk remains 26. Legacy DSL remains enabled for the protobuf plugin.
- Mainnet sync is awaiting an XBT fee source. Zeus embedded LND uses fee.url (default nodes.lightning.computer's BTC API), not P2P estimates. A service backed by the user's XBT node's estimatesmartfee can provide the required fee_by_block_target JSON over Tor.
- Lightning Receive field headings are Amount in sats, Description and Label above their inputs, with (optional) placeholders inside. Sender visibility/private-records explanations remain beneath their respective fields.

- Lightning Receive keeps Description/Label headings above inputs; placeholders are (optional - sender can see this) and (optional - for your own records), with wrapping to keep the notes readable. Removed separate explanatory text below these fields.

- App activity uses a 75% UI density scale, reducing dp/sp-based text, controls, dialogs and spacing by about 25% while preserving the system font-scale preference. QR display heights also scale by 75%; underlying encoded QR resolution is unchanged. System UI/keyboard density is unaffected.

- Dots menu is now an anchored popup over Home, dismissed by tapping outside or pressing Back. Home refresh does not rebuild the underlying screen while the popup is open. Secondary-screen Back/Cancel returns Home rather than the removed More page; the popup has no redundant Home entry.

- Removed Back up seed from the transient Home/loading screen. The backup/defer prompt is shown only immediately after creating a new wallet; subsequent boots provide seed backup through the dots menu only.

### Mainnet sync enabled — mempool.guide fees

- User authorized mempool.guide as the XBT fee source. Mainnet default is its advertised onion endpoint /api/v1/fees/precise, requested through the node Tor dialer; HTTP onion fee URLs are allowed only when Tor is enabled. HTTPS fee URLs remain supported. No clearnet fallback is configured.
- The fork fee adapter accepts mempool JSON and converts sat/vbyte to sat/kvbyte using upward rounding. fastest/halfHour/hour/economy map to 1/3/6/144 block targets; economy's 144-block mapping is an application policy, not an API guarantee. Invalid/missing/nonpositive/overflowing fee values and HTTP errors are rejected. Existing native fee floors still apply.
- Verified the onion API from Android through embedded Tor (TorTransportTest#mempoolOnionProvidesFees, passed). Native conversion/transport tests and Java unit tests, APK build and lint passed. Rebuilt native AAR and isolated mainnet APK installed.
- Mainnet LND logs confirmed the configured onion peer /Satoshi:29.5.0/Knots:20260508/ as a valid outbound peer, advertised height 981160, and advancing block/filter header downloads (past 57000 at checkpoint). A few short queries timed out and were automatically rescheduled with subsequent progress. Full initial sync and end-to-end mainnet payments are not yet validated. No funds were sent by the assistant.
- Mainnet on-chain fee preview now recognizes bc1/legacy mainnet output sizes as well as regtest; actual address checksum/network remains validated by the backend before payment review.
- Startup screen now displays separate Block headers and Filter headers progress bars with completed/peer-tip counts and percentages. Progress is read incrementally from the embedded node's startup log because GetInfo is not available in UNLOCKED state; requested download ranges are not counted as completed. Unknown target uses an indeterminate indicator, and 100% headers changes to finishing startup until SERVER_ACTIVE. Unit tests cover completed-vs-requested work, checkpoints, unknown target and percentage bounds.

- User switched mainnet testing to direct networking and supplied knots.knotsbits.com:8333. Live version handshake confirmed NODE_BLAKE2B and NODE_COMPACT_FILTERS. Mainnet test settings migrate once to that direct peer and mempool.guide HTTPS fee API; existing wallet and chain-sync data remain intact. This is one specified public peer, not automatic wider-network discovery. Tor remains configurable in Node connection.

- Mainnet discovery now replaces the fixed peer: dnsseed.bitcoin.dashjr-list-of-p2p-nodes.us and seed.bitcoin.haf.ovh, queried with BLAKE2b/network/witness/compact-filter service flags. Neutrino address selection requires these services; existing proof-of-work and activation checkpoint validation remains. Mainnet test migrates once to an empty peer setting (automatic discovery), preserving wallet and sync data. Advanced settings allow blank mainnet peer to restore discovery. Native changes are tracked in patches/lnd-dns-discovery.patch and applied by build-node.sh.
- Discovery validation: native config/checkpoint/chain-identity tests, Android unit tests, APK assembly and lint passed. Installed mainnet test APK. First seed returned four matching addresses; haf seed returned no records for x10000049 at test time. Connected automatically to 207.81.196.105:8333 and caught up on compact-filter headers at 981164. Separately, the mempool.guide HTTPS fee API returned HTTP 403 during this startup; discovery does not resolve that fee-service issue.

### Swap controller discovery — 2026-11-08

- Located newer local controller branch merchant-provider-api-20261107 at 4149e33, ahead of all fetched GitHub branches; isolated development branch wallet-discovery-20261108 lives in /home/claude-ubuntu/gpt/controller-wallet-workspace/swap-controller-startos. It includes existing signed customer quote/accept/status work (974e28d, 5d2235b), superseding earlier API-absence research based on main.
- Added dots-menu Swap controllers: manual URL/QR import, read-only connection review, persistent entries, identity-change rejection, removal, and directory search with local name/address filtering. HTTPS checks certificates; onion endpoints bootstrap embedded Tor and use unresolved SOCKS without direct fallback. No controller credentials or payments are sent.
- New controller read-only /v1/provider/info and /v1/providers contract, operator-configured public metadata, separate read-only volume and StartOS API interface supporting operator-configured TLS/Tor bindings. Discovery is transport-authenticated first-use pinning of advertised identity, not cryptographic proof of public-key ownership. No default public directory exists yet. Quote/payment integration remains separate.
- Validation: Android unit tests/build/lint, two read-only parser/SOCKS instrumentation tests, and one real HTTPS controller integration test passed. Controller Python HTTP/TLS tests (3), TypeScript check, JS bundling, and migration/restore regression checks passed, including 0.1.0:6 → 7. Built development 0.1.0:7 installer; the inherited runtime integrity check passed and the discovery container served metadata without authority mounts. This new workspace uses a development signing key; installed StartOS/Tor acceptance and production-key packaging remain outstanding.

- Installed integration on authorized StartOS LAN host 192.168.8.102 (dreary-collar), via dedicated SSH key: c-lightning #privkeyio:26.6.10:4 on knots-prerdts, xbt-cln #privkeyio:26.6.9:7 on bitcoind, swap-controller #privkeyio:0.1.0:8. Nodes fully synced; controller initialized, metadata configured, HTTPS and Tor interface verified. Fixed TLS ALPN from null to explicit http/1.1 after live requests negotiated unsupported h2. Both coordinator journals initialized and nodes restarted with stable identities; no grants, channels or payments. Controller automatic pairing remains waiting for bounded grants. No phone integration performed per user instruction. Verification artifacts in .tooling/start9-integration.

- Subsequent installed versions: c-lightning #privkeyio:26.6.10:5, xbt-cln #privkeyio:26.6.9:8, swap-controller #privkeyio:0.1.0:9. One-time local controller trust enabled on both nodes and verified across restart with unchanged identities. Both synced and waiting for channel liquidity; automatic grant issuance/renewal/revocation tested with fixtures. Journal archival remains outstanding as described in the backlog above.
- User supplied a shared test password for both CLN web interfaces in the conversation. Credential value deliberately omitted from repository notes.
- User authorized private mainnet channels: BTC 400,000 sats to ACINQ (03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f); XBT revised from 100,000 sats to all available funds, to 03f24ba054485ccfdb6791da40c08728aecbbea1663468812b405d1e1d7defe5fd@lqnnzvnqxivundt2rpjw57di5qfstutmr64udol5kviho2f2gbouzmqd.onion:9735. Both funding transactions broadcast. BTC capacity 400,000 sats, fee 166 sats, change 102,627 sats; txid b77dfab955fedb5ef5ea629d4d804996f318de94e0e676a7f2caffd0053c6d7f, awaiting 8 confirmations at last check. XBT capacity 49,974,834 sats, fee 166 sats, automatic on-chain reserve 25,000 sats; txid 568f277caf96129e94fa76c5959d2be5d3ccda91d674d488dc2499344951b75a, awaiting 3 confirmations. Both initially have zero inbound liquidity. The interrupted small-XBT opening sequence was stopped before submitting any XBT request; only the all-funds XBT channel was created. Evidence: controller-wallet-workspace/trust-build/channel-opens. Larger channel capacity does not increase the existing 10,000-sat automatic per-swap grant ceiling; review chain-specific limits before realistic exchange testing.

- 2026-11-09: User requested a 1,000,000-sat XBT per-swap ceiling. Implemented and installed xbt-cln #privkeyio:26.6.9:9 (commit d0a3d3a), preserving node identity/channel and BTC's 10,000-sat ceiling. Old automatically managed XBT grants refreshed safely; controller automatically re-paired. Live outgoing XBT limit is 1,000,000 sats; incoming XBT is currently 500,252 sats due to available inbound liquidity. Total grant budgets remain three times the selected per-swap amount; routing fee limits unchanged. Passed 32 runtime tests plus package/action checks. Evidence: controller-wallet-workspace/trust-build/xbt-million.

- 2026-11-09 swap preparation diagnosis (read-only; no swap record or payment created): user's 100,000-sat XBT invoice is valid, payee is the designated XBT peer, final CLTV 18, seven-day expiry. XBT peer had disconnected, producing getroutes code 205 (only source channel disabled); reconnected it. BTC peer was also disconnected; reconnected it. At the observed Neoxa quote, the BTC side was 384 sats, rejected by the incoming untrimmed-HTLC check. Direct read-only inspection rejected 384 BTC sats but accepted 3,840 BTC sats. Recommend a fresh 1,000,000-sat XBT recipient invoice for the next test, subject to current price. Improve errors to distinguish disconnected peers/no route from incoming amounts below protected HTLC limits: customer HTTPS currently collapses remote refusals into coordinator_reply_refused_or_unavailable, hiding these causes behind the generic UI error. Diagnostic artifacts under controller-wallet-workspace/trust-build/invoice-debug; invoice not copied into repository notes.

- 2026-11-09: Fixed a missed independent controller pricing ceiling: market_quote still limited XBT to 500,000 sats after node grants rose to 1,000,000. Installed swap-controller #privkeyio:0.1.0:10 with a 1,000,000-sat pricing ceiling, BTC still 10,000, and distinct UI errors for amount limits and insufficient order-book depth. Existing freshness/spread/slippage guards preserved. Live installed read-only Neoxa quote validated 1,000,000 XBT sats for 3,841 BTC sats at test time; both automatic pairings healthy. No swap or payment created. Four pricing/HTTP tests, twelve service lifecycle tests, action and migration checks passed. Evidence: controller-wallet-workspace/trust-build/controller-million.

- 2026-11-09: Fixed immediate service_busy failures from ordinary worker journal contention. Installed swap-controller #privkeyio:0.1.0:11: actions now wait up to five seconds to acquire the journal before dispatch, never replaying an action after it begins. Distinct pre-dispatch timeout message added. Four real-lock tests cover release, timeout, non-lock failures and no replay after downstream uncertainty; pricing/lifecycle/action/migration checks passed. Installed read-only status test under an intentionally held lock waited 0.64 seconds and succeeded; zero swaps before/after and both directions still automatically paired. Evidence: controller-wallet-workspace/trust-build/controller-lockwait.

- 2026-11-09: Exact 1,000,000-sat XBT invoice read-only preparation now passes route planning, market pricing and both HTTPS inspections, then fails cross_contract.inspection with contract_inspection_unavailable. Root cause: RESERVE_MSAT=50,000,000 requires 50,000 confirmed unreserved on-chain sats on each node, but XBT fundchannel(all) retained only 25,000; BTC has 102,627. Assistant missed this mismatch when funding the XBT channel. No swap created or payment sent. Requested an additional 25,000 XBT sats confirmed on-chain; new XBT deposit address bc1qmf7x9dcaes4wad7xn9mgdgt0wxupj8a4dktjn5. Keep the reserve protection; improve error specificity and ensure future all-funds channel allocation accounts for controller reserve requirements. Private invoice is only in local diagnostic artifacts.

- 2026-11-09: User explicitly requested reducing the controller reserve minimum to 25,000 sats rather than topping up. Installed swap-controller #privkeyio:0.1.0:12 with 25,000 confirmed unreserved on-chain sats required on each node in either direction; below-threshold errors now explain the reserve requirement. CLN's default 25,000-sat min-emergency-msat is an anchor-channel wallet fee reserve, including private anchor channels, not a public-only reserve. Passed threshold/exclusion tests, 22 contract/worker regressions and packaged service/action/migration checks. Exact user 1,000,000-sat XBT invoice passed full live preparation with commit disabled (prepared_read_only_no_record_written); no swap or payment created. Previous 25,000-sat top-up request is superseded and unnecessary. Evidence: controller-wallet-workspace/trust-build/controller-reserve25/live-readonly-probe.json.

### Swap integration checkpoint — 2026-11-09

- Both mainnet directions succeeded end to end: BTC to XBT settled 3,845 BTC sats for 1,000,000 XBT sats; XBT to BTC settled 1,000 BTC sats for 261,787 XBT sats, with a 4.502 BTC-sat outgoing route fee. The reverse recipient was a Zeus embedded wallet; explicitly selecting its bfx-lnd0 channel as a custom route hint supplied the route missing from its automatic invoices.
- Installed c-lightning #privkeyio:26.6.10:8, xbt-cln #privkeyio:26.6.9:12, swap-controller #privkeyio:0.1.0:15. All service health checks passed. Accept recipient final timelocks within saved route bounds; the current automatic outgoing budget is 576 blocks, while the incoming contract still requires the actual route delay plus 150 recovery/propagation blocks. Existing plans/grants retain their original bounds; only idle owned outgoing grants refresh automatically. Fee cap remains 10 sats and hop cap remains four.
- Added allowlisted actionable errors for invoice timing/amount/timelock, channel readiness, bounded route failure, unknown recipient without hints, reserve shortfall and invoice preparation. Unknown remote errors and uncertain mutation outcomes remain private. No automatic retry of payment dispatch was introduced.
- Package commits: BTC 668ce69 (branch local-controller-trust-20261108), XBT 04844a6 (same branch name in its repository), controller e9b5b80 (wallet-discovery-20261108). These contain source, runtime patch staging, regression tests, documentation and version upgrades. The phone app has not been tested against these mainnet swap services, per the earlier scope restriction.

- Private source checkpoints (2026-11-09): wallet `BitcoinMechanic/xbt-wallet` on `main`; BTC `BitcoinMechanic/btc-cln-startos-wallet-integration` and XBT `BitcoinMechanic/xbt-cln-startos-wallet-integration` on `local-controller-trust-20261108`; controller `BitcoinMechanic/swap-controller-wallet-integration` on `wallet-discovery-20261108`. Existing package origins are public and were not used for this push. Private package remotes are named `private-checkpoint`. Wallet unit tests and lint passed for this checkpoint; device/instrumentation tests were not rerun.

- Visibility clarification (2026-11-09): only the phone wallet remains private. The user explicitly authorized publishing the integration branches to the existing public repositories `BitcoinMechanic/btc-cln-startos`, `BitcoinMechanic/xbt-cln-startos`, and `BitcoinMechanic/swap-controller-startos`. These public origins are the package development destinations; the additional private checkpoint repositories remain redundant backups.

- Expiry/restart audit correction (2026-11-09): node authority journals last changed at 17:31:50.836710 UTC (BTC incoming terminal expired) and 17:31:50.941927 UTC (XBT outgoing terminal retired, no send intent), before the controller restart events. Controller logs explicitly show `.const() triggered` and `Restarting service...` at 17:32:23.816 and 17:32:37.793. Retirement therefore completed before the restarts. The controller discovery code subscribes reactively to each dependency's entire `getStatus` object; StartOS exposes full statusInfo including health. Routine node status changes can restart all controller daemons. Grant creation times and unchanged publication mtimes exclude grant renewal/publication rewriting at those restart times. The precise changed status field was not logged, so the health-message transition is a supported explanation, not directly recorded evidence. Fix overly broad status subscriptions/reconciliation and repeat the expiry test while monitoring restarts. Earlier wording that cleanup might have required the restart is superseded by these journal timestamps.

- Fixed the overly broad node-status subscription in controller #privkeyio:0.1.0:16 (2026-11-09). Local discovery maps SDK getStatus to package presence before registering its reactive const watch. Health messages, readiness, error and node stop/start timestamps no longer restart the controller; installation/removal, published grant credentials and bridge addresses remain watched. Tests exercise the installed SDK watcher implementation with actual asynchronous status notifications. TypeScript and package migration/bundle checks passed; installed worker, pairing and discovery health passed. The installation intentionally restarted only the controller at 17:40:47 UTC; Lightning node start times remained unchanged. A fresh unpaid-expiry cycle has not been run after this fix.
