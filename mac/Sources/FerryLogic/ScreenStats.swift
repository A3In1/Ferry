import Foundation

/// Mirroring numbers for one 2 s window, logged by the viewer. Times are microseconds on the Mac's
/// monotonic clock, except FRAME pts (the phone's capture time on its own clock): the clocks differ,
/// so one-way delay is relative, (received − pts) above the best seen this session, which is the
/// queueing the link added. Plus the phone's own STATS (write→ACK round trip, encode time).
public struct ScreenStats: Sendable {
    public static let window: UInt64 = 2_000_000

    private var start: UInt64
    private var received = 0, bytes = 0, shown = 0, behind = 0, dropped = 0
    private var showSum: UInt64 = 0, showMax: UInt64 = 0
    private var decodeSum: UInt64 = 0, decodeMax: UInt64 = 0, decodeN = 0
    private var best: Int64?
    private var delaySum: Int64 = 0, delayMax: Int64 = 0, delayN = 0
    private var jitterSum: Int64 = 0, jitterN = 0, prev: Int64?
    private var phone: Fsp.PhoneStats?

    public init(now: UInt64) { start = now }

    /// A FRAME arrived `at`. Config-only frames carry no capture time.
    public mutating func received(pts: UInt64, bytes: Int, config: Bool, at: UInt64) {
        received += 1
        self.bytes += bytes
        guard !config, pts > 0 else { return }
        let d = Int64(bitPattern: at &- pts)
        if let p = prev {
            jitterSum += abs(d - p)
            jitterN += 1
        }
        prev = d
        best = min(best ?? d, d)
        let above = d - best!
        delaySum += above
        delayMax = max(delayMax, above)
        delayN += 1
    }

    /// A picture went to the display layer `us` after its FRAME arrived (our queue + decode).
    public mutating func shown(after us: UInt64) {
        shown += 1
        showSum += us
        showMax = max(showMax, us)
    }

    /// One frame took `us` to decode.
    public mutating func decoded(us: UInt64) {
        decodeN += 1
        decodeSum += us
        decodeMax = max(decodeMax, us)
    }

    /// Decoded but not displayed because newer frames were already waiting.
    public mutating func skippedBehind() { behind += 1 }
    /// Not decoded: waiting for a keyframe, or a decode error.
    public mutating func dropped(_ n: Int = 1) { dropped += n }
    public mutating func phone(_ s: Fsp.PhoneStats) { phone = s }

    /// The window's log line once `window` has passed (a new window starts), else nil.
    public mutating func report(at now: UInt64) -> String? {
        guard now >= start + Self.window else { return nil }
        let line = Self.line(seconds: Double(now - start) / 1e6, received: received, bytes: bytes, shown: shown, behind: behind,
                             dropped: dropped, decodeAvgMs: decodeN > 0 ? Double(decodeSum) / Double(decodeN) / 1000 : nil,
                             decodeMaxMs: Double(decodeMax) / 1000, showAvgMs: shown > 0 ? Double(showSum) / Double(shown) / 1000 : nil,
                             showMaxMs: Double(showMax) / 1000,
                             delayAvgMs: delayN > 0 ? Double(delaySum) / Double(delayN) / 1000 : nil,
                             delayMaxMs: Double(delayMax) / 1000,
                             jitterMs: jitterN > 0 ? Double(jitterSum) / Double(jitterN) / 1000 : nil, phone: phone)
        start = now
        received = 0; bytes = 0; shown = 0; behind = 0; dropped = 0
        showSum = 0; showMax = 0; decodeSum = 0; decodeMax = 0; decodeN = 0; delaySum = 0; delayMax = 0; delayN = 0; jitterSum = 0; jitterN = 0
        phone = nil
        return line
    }

    static func line(seconds s: Double, received: Int, bytes: Int, shown: Int, behind: Int, dropped: Int, decodeAvgMs: Double?,
                     decodeMaxMs: Double, showAvgMs: Double?,
                     showMaxMs: Double, delayAvgMs: Double?, delayMaxMs: Double, jitterMs: Double?, phone: Fsp.PhoneStats?) -> String {
        func f(_ v: Double?, _ digits: Int = 1) -> String { v.map { String(format: "%.\(digits)f", $0) } ?? "-" }
        func t(_ v: UInt16) -> Double? { v == Fsp.PhoneStats.unknown ? nil : Double(v) / 10 }
        var out = "screen: received \(f(Double(received) / s)) fps (\(f(Double(bytes) * 8 / s / 1e6, 2)) Mbps), shown \(f(Double(shown) / s)) fps"
            + ", behind \(behind), dropped \(dropped), decode \(f(decodeAvgMs)) ms (max \(f(decodeMaxMs))), recv→shown \(f(showAvgMs)) ms (max \(f(showMaxMs)))"
            + ", delay +\(f(delayAvgMs)) ms (max \(f(delayMaxMs)), jitter \(f(jitterMs)))"
        guard let p = phone else { return out + " | phone: no STATS" }
        let ps = Double(max(p.windowMs, 1)) / 1000
        out += " | phone: encoded \(f(Double(p.encoded) / ps, 0)) fps, sent \(f(Double(p.sent) / ps, 0)), dropped \(p.dropped)"
            + ", \(f(Double(p.outBps) / 1e6, 2)) of \(f(Double(p.targetBps) / 1e6, 2)) Mbps"
            + ", capture→encoded \(f(t(p.encodeTenthMs))) ms, write \(f(t(p.writeTenthMs))) ms"
            + ", write→ack \(f(t(p.rttTenthMs))) ms (min \(f(t(p.rttMinTenthMs))))"
        // Capture → on screen ≈ encode + half the best round trip + queueing above best + our queue/decode (+ one refresh, not counted).
        if let e = t(p.encodeTenthMs), let r = t(p.rttMinTenthMs), let d = delayAvgMs, let sh = showAvgMs {
            out += " | est. capture→shown ≈ \(f(e + r / 2 + d + sh, 0)) ms"
        }
        return out
    }
}
