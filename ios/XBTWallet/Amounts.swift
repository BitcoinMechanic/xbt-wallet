import Foundation

enum AmountUnit: String, CaseIterable { case sats, xbt = "XBT", usd = "USD" }

enum Amounts {
    static let coin = Decimal(100_000_000)
    static func parse(_ text: String, unit: AmountUnit, usdRate: Decimal?) throws -> Int64 {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, cleaned.range(of: "^[0-9]+([.][0-9]+)?$", options: .regularExpression) != nil,
              let number = Decimal(string: cleaned, locale: Locale(identifier: "en_US_POSIX")), number > 0 else {
            throw WalletError("Enter a positive amount using digits and a decimal point.")
        }
        var sats = number
        switch unit {
        case .sats: break
        case .xbt: sats *= coin
        case .usd:
            guard let rate = usdRate, rate > 0 else { throw WalletError("A current XBT/USD rate is required to enter USD.") }
            sats = number / rate * coin
        }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &sats, 0, .down)
        if unit != .usd && rounded != sats { throw WalletError("Amounts must resolve to whole satoshis.") }
        guard rounded >= 1, rounded <= Decimal(2_100_000_000_000_000 as Int64) else {
            throw WalletError("Amount is outside the supported range.")
        }
        return NSDecimalNumber(decimal: rounded).int64Value
    }
    static func format(_ sats: Int64, unit: AmountUnit, usdRate: Decimal?) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US")
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = true
        var value = Decimal(sats)
        switch unit {
        case .sats: formatter.maximumFractionDigits = 0
        case .xbt: value /= coin; formatter.maximumFractionDigits = 8
        case .usd:
            guard let rate = usdRate else { return "USD unavailable" }
            value = value / coin * rate
            formatter.minimumFractionDigits = 2; formatter.maximumFractionDigits = 2
        }
        return "\(formatter.string(from: NSDecimalNumber(decimal: value)) ?? "—") \(unit.rawValue)"
    }
    static func entry(_ sats: Int64, unit: AmountUnit, usdRate: Decimal?) -> String {
        var value = Decimal(sats)
        if unit == .xbt { value /= coin }
        if unit == .usd {
            guard let rate = usdRate else { return "" }
            value = value / coin * rate
        }
        return NSDecimalNumber(decimal: value).stringValue
    }
}

struct ScanResult: Equatable {
    var invoice: String?
    var address: String?
    var sats: Int64?
    static func parse(_ raw: String) throws -> ScanResult {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count <= 16_384 else { throw WalletError("Scanned data is too long.") }
        let lower = text.lowercased()
        let lightning = lower.hasPrefix("lightning:") ? String(text.dropFirst(10)) : text
        if lightning.lowercased().hasPrefix("lnbc") || lightning.lowercased().hasPrefix("lnbcrt") {
            return ScanResult(invoice: lightning)
        }
        if lower.hasPrefix("bitcoin:") {
            guard let components = URLComponents(string: text) else { throw WalletError("Invalid payment QR code.") }
            var result = ScanResult(address: components.path)
            var names = Set<String>()
            for item in components.queryItems ?? [] {
                guard names.insert(item.name).inserted else { throw WalletError("Duplicate payment parameter: \(item.name)") }
                guard !item.name.hasPrefix("req-") else { throw WalletError("Unsupported required payment parameter: \(item.name)") }
                if item.name == "amount" { result.sats = try Amounts.parse(item.value ?? "", unit: .xbt, usdRate: nil) }
            }
            guard !(result.address ?? "").isEmpty else { throw WalletError("Payment address is missing.") }
            return result
        }
        if lower.hasPrefix("bc1") || lower.hasPrefix("bcrt1") || text.hasPrefix("1") || text.hasPrefix("3") {
            return ScanResult(address: text)
        }
        throw WalletError("This QR code does not contain a supported Lightning invoice or on-chain address.")
    }
}
