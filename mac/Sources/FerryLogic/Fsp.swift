import Foundation

/// FSP1, the screen protocol (docs/screen-protocol.md): `u8 type`, `u32 len` (BE), payload.
public enum Fsp {
    public static let magic = Data("FSP1".utf8)
    public static let maxLen = 8 << 20

    public enum Pointer: UInt8 { case down = 0, up, move, hide }

    /// Phone → Mac.
    public enum Message: Equatable, Sendable {
        case info(width: Int, height: Int, caps: UInt8) // caps bit1 control
        case end(reason: UInt8, detail: String)
    }

    public struct ProtocolError: Error, Equatable, Sendable { public let why: String }

    // MARK: Mac → phone

    public static let start = message(0x20, Data())
    /// SCROLL flags: bit0 continuous (trackpad/Magic Mouse deltas incl. momentum), bit1 end (the
    /// gesture and its momentum are over: lift the finger). 0 = a wheel step.
    public static let scrollContinuous: UInt8 = 1, scrollEnd: UInt8 = 2

    public static func scroll(_ x: Float, _ y: Float, dx: Float, dy: Float, flags: UInt8 = 0) -> Data {
        message(0x11, be(x) + be(y) + be(dx) + be(dy) + [flags])
    }
    /// KEY meta bits: shift extends the selection, option works by word.
    public static let keyShift: UInt8 = 1, keyOption: UInt8 = 2

    public static func key(_ code: UInt32, meta: UInt8 = 0) -> Data { message(0x12, be(code) + [meta]) }
    public static func text(_ s: String) -> Data { message(0x13, Data(s.utf8)) }
    /// KEEPALIVE (0x18): empty; keeps an idle control stream's link from timing out. Ignored by the phone.
    public static let keepalive = message(0x18, Data())
    public static func cursor(_ a: Pointer, _ x: Float, _ y: Float) -> Data { message(0x16, [a.rawValue] + be(x) + be(y)) }

    /// A Mac key press as KEY (editing and caret keys, as Android keycodes) or TEXT; nil for keys with
    /// nothing to send (tab, F-keys). `command` turns ←/→ into Home/End; callers drop other ⌘/⌃ combinations.
    public static func keyPress(keyCode: UInt16, characters: String?, shift: Bool = false, option: Bool = false,
                                command: Bool = false) -> Data? {
        let meta = (shift ? keyShift : 0) | (option && !command ? keyOption : 0)
        let android: UInt32? = switch keyCode {
        case 51: 67 // delete → DEL
        case 117: 112 // forward delete
        case 36, 76: 66 // return, enter
        case 123: command ? 122 : 21 // ← (⌘← = start of line)
        case 124: command ? 123 : 22 // →
        case 125: command ? 123 : 20 // ↓
        case 126: command ? 122 : 19 // ↑
        case 115: 122 // home
        case 119: 123 // end
        default: nil
        }
        if let android { return key(android, meta: meta) }
        guard !command, let c = characters, !c.isEmpty,
              !c.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F || (0xF700...0xF8FF).contains($0.value) })
        else { return nil }
        return text(c)
    }

    public static func message(_ type: UInt8, _ payload: some Sequence<UInt8>) -> Data {
        let p = Data(payload)
        return Data([type]) + be(UInt32(p.count)) + p
    }

    static func be<T: FixedWidthInteger>(_ v: T) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }
    static func be(_ v: Float) -> Data { be(v.bitPattern) }
}

/// Incremental FSP1 reader for the phone → Mac direction: feed chunks as they arrive.
public struct FspParser {
    private var buf = Data()
    private var sawMagic = false

    public init() {}

    /// Complete messages in `chunk` (plus what was buffered). Unknown types are skipped.
    public mutating func push(_ chunk: Data) throws(Fsp.ProtocolError) -> [Fsp.Message] {
        buf.append(chunk)
        var out: [Fsp.Message] = []
        var i = buf.startIndex
        defer { buf.removeSubrange(buf.startIndex..<i) }
        if !sawMagic {
            guard buf.count >= 4 else { return out }
            guard buf.prefix(4) == Fsp.magic else { throw .init(why: "bad magic") }
            sawMagic = true
            i += 4
        }
        while buf.endIndex - i >= 5 {
            let len = Int(u32(buf, i + 1))
            guard len <= Fsp.maxLen else { throw .init(why: "message of \(len) bytes") }
            guard buf.endIndex - i - 5 >= len else { break }
            let type = buf[i], p = Data(buf[(i + 5)..<(i + 5 + len)])
            i += 5 + len
            if let m = try decode(type, p) { out.append(m) }
        }
        return out
    }

    private func decode(_ type: UInt8, _ p: Data) throws(Fsp.ProtocolError) -> Fsp.Message? {
        func need(_ n: Int) throws(Fsp.ProtocolError) { if p.count < n { throw .init(why: "short message \(type)") } }
        switch type {
        case 0x01:
            try need(5)
            return .info(width: Int(u16(p, 0)), height: Int(u16(p, 2)), caps: p[4])
        case 0x03:
            try need(1)
            return .end(reason: p[0], detail: String(decoding: p.dropFirst(), as: UTF8.self))
        default:
            return nil
        }
    }

    /// `at` is an absolute index (payloads are fresh `Data`, so theirs start at 0).
    private func u16(_ d: Data, _ at: Int) -> UInt16 { UInt16(d[at]) << 8 | UInt16(d[at + 1]) }
    private func u32(_ d: Data, _ at: Int) -> UInt32 { UInt32(u16(d, at)) << 16 | UInt32(u16(d, at + 2)) }
}
