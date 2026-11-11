import Foundation
import Lndmobile

struct WalletError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    init(_ message: String) { self.message = message }
}

struct Reply {
    let fields: [String: Any]
    init(_ fields: [String: Any] = [:]) { self.fields = fields }
    init(data: Data) throws {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw WalletError("The wallet engine returned an invalid JSON response.")
        }
        fields = object
    }
    func text(_ key: String) -> String {
        if let s = fields[key] as? String { return s }
        return (fields[key] as? NSNumber)?.stringValue ?? ""
    }
    func int(_ key: String) -> Int64 { Int64(text(key)) ?? 0 }
    func bool(_ key: String) -> Bool { fields[key] as? Bool ?? false }
    func object(_ key: String) -> Reply { Reply(fields[key] as? [String: Any] ?? [:]) }
    func array(_ key: String) -> [Reply] { (fields[key] as? [[String: Any]] ?? []).map(Reply.init) }
}

private final class Callback: NSObject, LndmobileCallbackProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var completed = false
    private let receive: (Result<Data, Error>) -> Void
    init(_ receive: @escaping (Result<Data, Error>) -> Void) { self.receive = receive }
    func finish(_ result: Result<Data, Error>) {
        lock.lock()
        guard !completed else { lock.unlock(); return }
        completed = true
        lock.unlock()
        receive(result)
    }
    func onResponse(_ data: Data?) { finish(.success(data ?? Data())) }
    func onError(_ error: Error?) { finish(.failure(error ?? WalletError("Wallet engine returned an unspecified error."))) }
}

private final class StreamCallback: NSObject, LndmobileRecvStreamProtocol {
    let continuation: AsyncThrowingStream<Reply, Error>.Continuation
    init(_ continuation: AsyncThrowingStream<Reply, Error>.Continuation) { self.continuation = continuation }
    func onResponse(_ data: Data?) {
        do {
            let reply = try Reply(data: data ?? Data())
            continuation.yield(reply)
            if ["SUCCEEDED", "FAILED"].contains(reply.text("status")) || reply.fields["chan_close"] != nil {
                continuation.finish()
            }
        } catch { continuation.finish(throwing: error) }
    }
    func onError(_ error: Error?) { continuation.finish(throwing: error ?? WalletError("Wallet stream ended without a result. Check payment history.")) }
}

final class NativeRPC {
    private func callback(timeout: TimeInterval, operation: String,
                          invoke: @escaping (Callback) -> Void) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let callback = Callback { continuation.resume(with: $0) }
            DispatchQueue.global(qos: .userInitiated).async { invoke(callback) }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                callback.finish(.failure(WalletError("\(operation) timed out after \(Int(timeout)) seconds. The engine may still complete the request; check history before retrying a transaction.")))
            }
        }
    }
    func start(directory: URL, regtest: Bool = false) async throws {
        var args = "--lnddir=\(directory.path) --bitcoin.active --bitcoin.\(regtest ? "regtest" : "mainnet") --bitcoin.node=neutrino --nolisten --norest --rpclisten=127.0.0.1:0 --tlsdisableautofill --accept-keysend"
        if regtest {
            args += " --bitcoin.blake2b-activation-height=1 --neutrino.connect=127.0.0.1:19444"
        } else {
            args += " --fee.url=https://mempool.guide/api/v1/fees/precise"
        }
        let launchArgs = args
        _ = try await callback(timeout: 90, operation: "Start wallet") { LndmobileStart(launchArgs, $0) }
    }
    func stop() async throws {
        _ = try await callback(timeout: 65, operation: "Stop wallet") { LndmobileStopRuntime($0) }
    }
    func call(_ method: String, _ params: [String: Any] = [:], timeout: TimeInterval = 35) async throws -> Reply {
        let input = String(decoding: try JSONSerialization.data(withJSONObject: params), as: UTF8.self)
        let data = try await callback(timeout: timeout, operation: method) { LndmobileJSONCall(method, input, $0) }
        return try Reply(data: data)
    }
    func stream(_ method: String, _ params: [String: Any]) throws -> AsyncThrowingStream<Reply, Error> {
        let input = String(decoding: try JSONSerialization.data(withJSONObject: params), as: UTF8.self)
        return AsyncThrowingStream { continuation in
            let callback = StreamCallback(continuation)
            DispatchQueue.global(qos: .userInitiated).async { LndmobileJSONStream(method, input, callback) }
            DispatchQueue.global().asyncAfter(deadline: .now() + 120) {
                continuation.finish(throwing: WalletError("\(method) did not return a final result within 120 seconds. It may still complete in the node. Check history before retrying."))
            }
        }
    }
}
