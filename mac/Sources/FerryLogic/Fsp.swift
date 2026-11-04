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
        case quality(bitrate: UInt32) // the phone adapted its encoder to the link
        case stats(PhoneStats) // every ~2 s: what the phone's encoder and sender did
    }

    /// STATS (0x05): one window of the phone's encoder/sender numbers. Times in 0.1 ms; `unknown` = none.
    public struct PhoneStats: Equatable, Sendable {
        public static let unknown: UInt16 = 0xFFFF
        public var windowMs, encoded, sent, dropped: UInt16
        public var targetBps, outBps: UInt32
        public var encodeTenthMs, writeTenthMs, rttTenthMs, rttMinTenthMs: UInt16

        public init(windowMs: UInt16, encoded: UInt16, sent: UInt16, dropped: UInt16, targetBps: UInt32, outBps: UInt32,
                    encodeTenthMs: UInt16, writeTenthMs: UInt16, rttTenthMs: UInt16, rttMinTenthMs: UInt16) {
            self.windowMs = windowMs
            self.encoded = encoded
            self.sent = sent
            self.dropped = dropped
            self.targetBps = targetBps
            self.outBps = outBps
            self.encodeTenthMs = encodeTenthMs
            self.writeTenthMs = writeTenthMs
            self.rttTenthMs = rttTenthMs
            self.rttMinTenthMs = rttMinTenthMs
        }
    }

    public struct ProtocolError: Error, Equatable, Sendable { public let why: String }

    // MARK: Mac → phone

    public static func start(maxSize: UInt16 = 1920, bitrate: UInt32 = 8_000_000, video: Bool) -> Data {
        message(0x20, be(maxSize) + be(bitrate) + [video ? 1 : 0])
    }
    public static func touch(_ a: Pointer, _ x: Float, _ y: Float) -> Data { message(0x10, [a.rawValue] + be(x) + be(y)) }
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
    public static func nav(_ n: Nav) -> Data { message(0x14, [n.rawValue]) }
    public static let keyframe = message(0x15, Data())
    /// ACK (0x17): the newest FRAME received, by its pts, so the phone can time the link.
    public static func ack(pts: UInt64) -> Data { message(0x17, be(pts)) }
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

    /// END reason as a line for the viewer window. `detail` is for logs, never shown.
    public static func endText(reason: UInt8, detail: String) -> String {
        switch reason {
        case 0: return "Screen sharing ended."
        case 1: return "Screen sharing was stopped on the phone."
        case 2: return "The phone is locked. Unlock it, then click Reconnect."
        case 3: return detail.contains("control") ? "Turn on Ferry control on the phone, then click Reconnect."
            : "Screen sharing wasn't allowed. Click Reconnect, then tap Start now on the phone."
        default: return "Couldn't connect to the phone. Make sure it's nearby with Ferry running, then click Reconnect."
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
            return .frame(pts: UInt64(u32(p, 0)) << 32 | UInt64(u32(p, 4)), flags: p[8], data: p.dropFirst(9))
        case 0x03:
            try need(1)
            return .end(reason: p[0], detail: String(decoding: p.dropFirst(), as: UTF8.self))
        case 0x04:
            try need(4)
            return .quality(bitrate: u32(p, 0))
        case 0x05:
            try need(24)
            return .stats(.init(windowMs: u16(p, 0), encoded: u16(p, 2), sent: u16(p, 4), dropped: u16(p, 6),
                                targetBps: u32(p, 8), outBps: u32(p, 12), encodeTenthMs: u16(p, 16),
                                writeTenthMs: u16(p, 18), rttTenthMs: u16(p, 20), rttMinTenthMs: u16(p, 22)))
        default:
            return nil
        }
    }

    /// `at` is an absolute index (payloads are fresh `Data`, so theirs start at 0; FRAME data is a slice of one).
    private func u16(_ d: Data, _ at: Int) -> UInt16 { UInt16(d[at]) << 8 | UInt16(d[at + 1]) }
    private func u32(_ d: Data, _ at: Int) -> UInt32 { UInt32(u16(d, at)) << 16 | UInt32(u16(d, at + 2)) }
}

/// H.264 Annex-B (what MediaCodec emits) → NAL units / AVCC for VideoToolbox.
public enum H264 {
    public static let sps: UInt8 = 7, pps: UInt8 = 8

    /// NAL units between 3- or 4-byte start codes (no start codes, no trailing zero bytes).
    public static func nalUnits(_ d: Data) -> [Data] {
        d.withUnsafeBytes { b in
            var ranges: [Range<Int>] = []
            nalRanges(b, into: &ranges)
            return ranges.map { Data(b[$0]) }
        }
    }

    /// Where the NAL units of an Annex-B buffer are, appended to `out` (callers reuse it, so no per-frame array).
    public static func nalRanges(_ b: UnsafeRawBufferPointer, into out: inout [Range<Int>]) {
        var start: Int?
        var i = 0
        func close(_ end: Int) {
            guard let s = start else { return }
            var e = end
            while e > s, b[e - 1] == 0 { e -= 1 } // a NAL never ends in 0x00; these belong to a 4-byte start code
            if e > s { out.append(s..<e) }
        }
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                close(i)
                i += 3
                start = i
            } else { i += 1 }
        }
        close(b.count)
    }

    /// Bytes `writeAVCC` needs for these NALs.
    public static func avccSize(_ nals: [Range<Int>]) -> Int { nals.reduce(0) { $0 + 4 + $1.count } }

    /// The NALs at `nals` in `src` with 4-byte length prefixes, straight into `dst` (≥ `avccSize` bytes):
    /// one copy from the FRAME payload into the sample's memory.
    public static func writeAVCC(_ src: UnsafeRawBufferPointer, _ nals: [Range<Int>], to dst: UnsafeMutableRawBufferPointer) {
        var o = 0
        for r in nals {
            withUnsafeBytes(of: UInt32(r.count).bigEndian) { dst.baseAddress!.advanced(by: o).copyMemory(from: $0.baseAddress!, byteCount: 4) }
            dst.baseAddress!.advanced(by: o + 4).copyMemory(from: src.baseAddress!.advanced(by: r.lowerBound), byteCount: r.count)
            o += 4 + r.count
        }
    }

    public static func type(_ nal: Data) -> UInt8 { (nal.first ?? 0) & 0x1F }

    /// NALs with 4-byte big-endian length prefixes (the format CMVideoFormatDescription uses with header length 4).
    public static func avcc(_ nals: [Data]) -> Data {
        var out = Data(capacity: nals.reduce(0) { $0 + 4 + $1.count })
        for n in nals {
            out += Fsp.be(UInt32(n.count))
            out += n
        }
        return out
    }
}
