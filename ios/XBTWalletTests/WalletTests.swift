import XCTest
import UIKit
@testable import XBTWallet

final class WalletTests: XCTestCase {
    func testLogoIsPackagedInAssetCatalog() {
        let image = UIImage(named: "xbt_logo")
        XCTAssertNotNil(image)
        if let image { XCTAssertEqual(image.size.width / image.size.height, 3, accuracy: 0.01) }
    }
    func testExactAmountsAndFiatConversion() throws {
        XCTAssertEqual(try Amounts.parse("0.00000001", unit: .xbt, usdRate: nil), 1)
        XCTAssertEqual(try Amounts.parse("50", unit: .usd, usdRate: 250), 20_000_000)
        XCTAssertEqual(try Amounts.parse("0.01", unit: .usd, usdRate: 3), 333_333)
        XCTAssertThrowsError(try Amounts.parse("1.5", unit: .sats, usdRate: nil))
        XCTAssertThrowsError(try Amounts.parse("0.000000001", unit: .xbt, usdRate: nil))
        XCTAssertThrowsError(try Amounts.parse("10", unit: .usd, usdRate: nil))
        XCTAssertThrowsError(try Amounts.parse("-1", unit: .sats, usdRate: nil))
        XCTAssertThrowsError(try Amounts.parse("1e5", unit: .sats, usdRate: nil))
        XCTAssertThrowsError(try Amounts.parse("999999999999999999999999", unit: .sats, usdRate: nil))
        XCTAssertEqual(Amounts.format(1_000_000, unit: .sats, usdRate: nil), "1,000,000 sats")
    }
    func testPaymentQRRoutingAndRequiredParameters() throws {
        let result = try ScanResult.parse("bitcoin:bc1qexample?amount=0.00123&label=Coffee")
        XCTAssertEqual(result.address, "bc1qexample")
        XCTAssertEqual(result.sats, 123_000)
        XCTAssertEqual(try ScanResult.parse("lightning:lnbcExample").invoice, "lnbcExample")
        XCTAssertThrowsError(try ScanResult.parse("bitcoin:bc1qexample?req-unknown=x"))
        XCTAssertThrowsError(try ScanResult.parse("bitcoin:bc1qexample?amount=1&amount=2"))
        XCTAssertThrowsError(try ScanResult.parse("https://malicious.example"))
    }
    func testProtobufInt64PrecisionAndByteValues() throws {
        let reply = try Reply(data: Data(#"{"value":"9007199254740993","hash":"AQID","ready":true}"#.utf8))
        XCTAssertEqual(reply.int("value"), 9_007_199_254_740_993)
        XCTAssertEqual(Data(base64Encoded: reply.text("hash")), Data([1, 2, 3]))
        XCTAssertTrue(reply.bool("ready"))
    }
    func testJournalSurvivesReopenAndRejectsDuplicate() throws {
        let store = try WalletStore(test: true)
        let reference = UUID().uuidString
        let id = try store.begin("lightning", reference: reference)
        let reopened = try WalletStore(test: true)
        XCTAssertEqual(reopened.attempts.last?.id, id)
        XCTAssertThrowsError(try reopened.begin("lightning", reference: reference))
        try reopened.finish(id, state: "failed", result: "No route")
        XCTAssertNoThrow(try reopened.begin("lightning", reference: reference))
    }
    private func waitForWallet(_ rpc: NativeRPC) async throws {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline {
            if try await rpc.call("getState").text("state") == "NON_EXISTING" { return }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        try? await rpc.stop()
        throw WalletError("Native wallet did not reach NON_EXISTING within 20 seconds.")
    }
    func testNativeEngineStartsGeneratesSeedAndRestarts() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("engine-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let rpc = NativeRPC()
        try await rpc.start(directory: directory, regtest: true)
        try await waitForWallet(rpc)
        let seed = try await rpc.call("genSeed")
        XCTAssertEqual((seed.fields["cipher_seed_mnemonic"] as? [String])?.count, 24)
        try await rpc.stop()
        try await rpc.start(directory: directory, regtest: true)
        try await waitForWallet(rpc)
        try await rpc.stop()
        try FileManager.default.removeItem(at: directory)
    }
}
