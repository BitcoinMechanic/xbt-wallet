import Foundation
import Security

/// Device-only secrets, never UserDefaults, log output or iCloud backup.
enum Secrets {
    static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "org.xbt.wallet.ios",
         kSecAttrAccount as String: account]
    }
    static func read(_ account: String) throws -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw WalletError("Cannot read wallet secrets from Keychain (\(status)).")
        }
        return data
    }
    static func write(_ account: String, _ data: Data) throws {
        let q = query(account)
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(q as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(q.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw WalletError("Cannot save wallet secrets in Keychain (\(status)).") }
    }
    static func randomPassword() throws -> Data {
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw WalletError("Secure random generator failed (\(status)).") }
        return Data(bytes.base64EncodedString().utf8)
    }
}

struct Attempt: Codable, Identifiable {
    let id: UUID
    let kind: String
    let reference: String
    let created: Date
    var state: String
    var result: String
}

/// Persist before dispatch. An interrupted mutation is never retried automatically.
final class WalletStore {
    let directory: URL
    let secretPrefix: String
    private(set) var attempts: [Attempt] = []
    private var journal: URL { directory.appendingPathComponent("attempts.json") }
    init(test: Bool = false) throws {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        directory = base.appendingPathComponent(test ? "XBT-iOS-tests" : "XBT-mainnet", isDirectory: true)
        secretPrefix = test ? "test." : "mainnet."
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var url = directory
        var resource = URLResourceValues()
        resource.isExcludedFromBackup = true
        try url.setResourceValues(resource)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        if FileManager.default.fileExists(atPath: journal.path) {
            attempts = try JSONDecoder().decode([Attempt].self, from: Data(contentsOf: journal))
        }
    }
    func begin(_ kind: String, reference: String) throws -> UUID {
        if attempts.contains(where: { $0.kind == kind && $0.reference == reference && $0.state != "failed" }) {
            throw WalletError("This request has already been submitted. Check payment history before trying again.")
        }
        let attempt = Attempt(id: UUID(), kind: kind, reference: reference, created: Date(), state: "pending", result: "")
        attempts.append(attempt)
        do { try save() } catch { attempts.removeLast(); throw error }
        return attempt.id
    }
    func finish(_ id: UUID, state: String, result: String) throws {
        guard let i = attempts.firstIndex(where: { $0.id == id }) else { throw WalletError("Transaction journal entry is missing.") }
        attempts[i].state = state
        attempts[i].result = result
        try save()
    }
    private func save() throws {
        try JSONEncoder().encode(attempts).write(to: journal, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
