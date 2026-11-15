import Foundation

private let codeWords = try! NSRegularExpression(
    pattern: #"(?i)(?<!zip )(?<!postal )\b(?:codes?|otp|passcode|password|pin|verification|verify|one[- ]?time|2fa)\b"#)
private let codeShapes = try! NSRegularExpression(pattern: #"(?<!\d)(?:\d{4,8}|\d{3}[- ]\d{3})(?!\d)"#)
private let moneyOrAccount: Set<String> = ["rs", "inr", "usd", "eur", "gbp", "aed", "ending", "acct", "account", "card", "a/c"]
private let unitsAfter: Set<String> = ["rs", "inr", "usd", "eur", "gbp", "aed", "hrs", "hours", "am", "pm"]
private let months: Set<String> = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
                                   "january", "february", "march", "april", "june", "july", "august", "september",
                                   "october", "november", "december"]

/// The one-time code in a text ("482913 is your OTP", "G-482913", "code: 123-456"), digits only, or nil.
/// A 4–8 digit number within 80 characters of code/OTP/verification/password/PIN (the nearest wins),
/// skipping phone numbers, amounts, dates, times and masked account numbers.
public func oneTimeCode(in text: String) -> String? {
    let ns = text as NSString
    let all = NSRange(location: 0, length: ns.length)
    let words = codeWords.matches(in: text, range: all).map(\.range)
    guard !words.isEmpty else { return nil }
    var best: (code: String, gap: Int)?
    for m in codeShapes.matches(in: text, range: all) {
        let r = m.range
        let code = ns.substring(with: r)
        guard plausibleCode(code, before: ns.substring(to: r.location), after: ns.substring(from: NSMaxRange(r))) else { continue }
        let gap = words.map { w in w.location >= NSMaxRange(r) ? w.location - NSMaxRange(r) : max(0, r.location - NSMaxRange(w)) }.min()!
        if gap <= 80, gap < best?.gap ?? .max { best = (code.filter(\.isNumber), gap) }
    }
    return best?.code
}

private func plausibleCode(_ code: String, before: String, after: String) -> Bool {
    let b = before.last, b2 = before.dropLast().last, a = after.first, a2 = after.dropFirst().first
    if b?.isLetter == true || a?.isLetter == true || a?.isNumber == true { return false } // ref ids, XX1234
    if let b, "+#*$€£₹¥".contains(b) { return false }
    if let b, "-./:,".contains(b), b2?.isNumber == true { return false } // 555-1234, 15/03/2024, 1.2345
    if let a, "-./:,".contains(a), a2?.isNumber == true { return false } // 2024-03-15, 1234.56, 12:30
    if b == " ", b2?.isNumber == true || b2 == ")" { return false } // 1800 123 4567, (555) 1234
    if a == " ", a2?.isNumber == true { return false }
    if a == "%" { return false }
    let prev = before.lowercased().split { !$0.isLetter && $0 != "/" }.suffix(2).map(String.init)
    let year = code.count == 4 && (code.hasPrefix("19") || code.hasPrefix("20"))
    if let w = prev.last, moneyOrAccount.contains(w) || year && months.contains(w) { return false }
    if prev == ["ending", "in"] { return false }
    if let w = after.lowercased().split(whereSeparator: { !$0.isLetter }).first, unitsAfter.contains(String(w)),
       after.prefix(while: { !$0.isLetter }).allSatisfy({ $0 == " " }) {
        return false
    }
    return true
}

/// Same phone number, ignoring formatting and country prefix (compares the last 10 digits).
/// Senders without digits (alphanumeric ids like "HDFCBK") compare case-insensitively.
public func sameNumber(_ a: String, _ b: String) -> Bool {
    let x = a.filter(\.isNumber), y = b.filter(\.isNumber)
    if x.isEmpty || y.isEmpty { return a.lowercased() == b.lowercased() }
    return x.suffix(10) == y.suffix(10)
}

/// What's typed in a search box, if it could be a phone number: only digits and "+-() ", at least 3 digits.
public func typedNumber(_ query: String) -> String? {
    let q = query.trimmingCharacters(in: .whitespaces)
    guard q.allSatisfy({ $0.isNumber || "+-() ".contains($0) }), q.filter(\.isNumber).count >= 3 else { return nil }
    return q
}

/// Whether a conversation or call matches a search: `query` found in `text` (ignoring case and accents),
/// or a typed number found in `number` (ignoring its spacing). An empty query matches everything.
public func matchesSearch(_ query: String, text: [String], number: String) -> Bool {
    let q = query.trimmingCharacters(in: .whitespaces)
    if q.isEmpty { return true }
    if text.contains(where: { $0.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil }) { return true }
    guard let typed = typedNumber(q) else { return number.localizedCaseInsensitiveContains(q) }
    return number.filter(\.isNumber).contains(typed.filter(\.isNumber))
}
