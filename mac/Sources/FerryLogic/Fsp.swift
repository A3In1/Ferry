import Foundation

/// FSP1, the screen protocol (docs/screen-protocol.md): `u8 type`, `u32 len` (BE), payload.
public enum Fsp {
    public static let magic = Data("FSP1".utf8)
    public static let maxLen = 8 << 20

    public enum Pointer: UInt8 { case down = 0, up, move, hide } // hide: CURSOR only
    public enum Nav: UInt8 { case back = 1, home, recents, notifications }

    /// Phone → Mac.
    public enum Message: Equatable, Sendable {
        case info(width: Int, height: Int, caps: UInt8) // caps bit0 video, bit1 control
        case frame(pts: UInt64, flags: UInt8, data: Data) // flags bit0 keyframe, bit1 codec config
        case end(reason: UInt8, detail: String)
    }

    public struct ProtocolError: Error, Equatable, Sendable { public let why: String }

    // MARK: Mac → phone

    public static func start(maxSize: UInt16 = 1280, bitrate: UInt32 = 8_000_000, video: Bool) -> Data {
        message(0x20, be(maxSize) + be(bitrate) + [video ? 1 : 0])
    }
    public static func touch(_ a: Pointer, _ x: Float, _ y: Float) -> Data { message(0x10, [a.rawValue] + be(x) + be(y)) }
    public static func scroll(_ x: Float, _ y: Float, dx: Float, dy: Float) -> Data {
        message(0x11, be(x) + be(y) + be(dx) + be(dy))
    }
    public static func key(_ code: UInt32) -> Data { message(0x12, be(code)) }
    public static func text(_ s: String) -> Data { message(0x13, Data(s.utf8)) }
    public static func nav(_ n: Nav) -> Data { message(0x14, [n.rawValue]) }
    public static let keyframe = message(0x15, Data())
    public static func cursor(_ a: Pointer, _ x: Float, _ y: Float) -> Data { message(0x16, [a.rawValue] + be(x) + be(y)) }

    /// A Mac key press as KEY (delete, return) or TEXT; nil for keys with nothing to type (arrows, tab, F-keys).
    /// Callers drop ⌘/⌃ combinations first.
    public static func keyPress(keyCode: UInt16, characters: String?) -> Data? {
        switch keyCode {
        case 51: return key(67)
        case 36, 76: return key(66)
        default:
            guard let c = characters, !c.isEmpty,
                  !c.unicodeScalars.contains(where: { $0.value < 0x20 || $0.value == 0x7F || (0xF700...0xF8FF).contains($0.value) })
            else { return nil }
            return text(c)
        }
    }

    public static func message(_ type: UInt8, _ payload: some Sequence<UInt8>) -> Data {
        let p = Data(payload)
        return Data([type]) + be(UInt32(p.count)) + p
    }

    static func be<T: FixedWidthInteger>(_ v: T) -> Data { withUnsafeBytes(of: v.bigEndian) { Data($0) } }
    static func be(_ v: Float) -> Data { be(v.bitPattern) }

    /// END reason as a line for the viewer window.
    public static func endText(reason: UInt8, detail: String) -> String {
        let suffix = detail.isEmpty ? "" : " (\(detail))"
        switch reason {
        case 0: return "Mirroring ended"
        case 1: return "Stopped on the phone"
        case 2: return "Phone locked: unlock it and press Reconnect"
        case 3: return detail.contains("control") ? "Turn on Ferry control on the phone" : "Allow screen sharing on the phone"
        default: return "Something went wrong\(suffix)"
        }
    }
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
        case 0x02:
            try need(9)
            return .frame(pts: UInt64(u32(p, 0)) << 32 | UInt64(u32(p, 4)), flags: p[8], data: Data(p.dropFirst(9)))
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

/// H.264 Annex-B (what MediaCodec emits) → NAL units / AVCC for VideoToolbox.
public enum H264 {
    public static let sps: UInt8 = 7, pps: UInt8 = 8

    /// NAL units between 3- or 4-byte start codes (no start codes, no trailing zero bytes).
    public static func nalUnits(_ d: Data) -> [Data] {
        let b = [UInt8](d)
        var out: [Data] = [], start: Int?
        var i = 0
        func close(_ end: Int) {
            guard let s = start else { return }
            var e = end
            while e > s, b[e - 1] == 0 { e -= 1 } // a NAL never ends in 0x00; these belong to a 4-byte start code
            if e > s { out.append(Data(b[s..<e])) }
        }
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                close(i)
                i += 3
                start = i
            } else { i += 1 }
        }
        close(b.count)
        return out
    }

    public static func type(_ nal: Data) -> UInt8 { (nal.first ?? 0) & 0x1F }

    /// NALs with 4-byte big-endian length prefixes (the format CMVideoFormatDescription uses with header length 4).
    public static func avcc(_ nals: [Data]) -> Data {
        nals.reduce(into: Data()) { $0 += Fsp.be(UInt32($1.count)) + $1 }
    }
}
