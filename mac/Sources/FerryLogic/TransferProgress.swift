import Foundation

/// One transfer's progress, built from the core's per-file events. Files go up to 8 at a time,
/// so bytes are summed per file, and speed comes from a short sliding window (resumed files
/// start part-way, which mustn't count as speed).
public struct TransferProgress: Equatable, Sendable {
    public var names: [String]
    /// 0 until the core reports it (a send's `Sending` event, a receive's `Offered`).
    public var totalBytes: UInt64
    private var done: [UInt32: UInt64] = [:]
    private var finished: Set<UInt32> = []
    private var current: UInt32?
    private var samples: [Sample] = []

    private struct Sample: Equatable, Sendable {
        let at: Date
        let bytes: UInt64
    }

    static let window: TimeInterval = 3

    public init(names: [String], totalBytes: UInt64 = 0) {
        self.names = names
        self.totalBytes = totalBytes
    }

    public var bytesDone: UInt64 { min(done.values.reduce(0, +), totalBytes > 0 ? totalBytes : .max) }

    /// 0...1, or nil while the size isn't known yet (show an indeterminate bar).
    public var fraction: Double? {
        guard totalBytes > 0 else { return names.isEmpty || finished.isEmpty ? nil : 1 }
        return min(1, Double(bytesDone) / Double(totalBytes))
    }

    public var fileCount: Int { names.count }

    /// 1-based number of the file being worked on, for "File 2 of 3".
    public var fileNumber: Int { min(finished.count + 1, max(names.count, 1)) }

    /// The file that moved last (several can be in flight).
    public var currentName: String? {
        guard let i = current.map(Int.init), i < names.count else { return nil }
        return names[i]
    }

    public mutating func progress(index: UInt32, done bytes: UInt64, total: UInt64, at now: Date = .now) {
        done[index] = min(bytes, total)
        if bytes >= total { finished.insert(index) }
        current = index
        // Speed is the slope between samples, so a resumed file's head start (its first report) never counts.
        samples.append(Sample(at: now, bytes: bytesDone))
        while samples.count > 2, now.timeIntervalSince(samples[0].at) > Self.window { samples.removeFirst() }
        if samples.count > 64 { samples.removeFirst(samples.count - 64) }
    }

    /// Saved or failed: the file no longer counts as in progress.
    public mutating func finish(index: UInt32) {
        finished.insert(index)
    }

    /// Bytes per second over the last few seconds; nil until there's enough to say.
    public func speed(at now: Date = .now) -> Double? {
        guard let first = samples.first, let last = samples.last else { return nil }
        let secs = last.at.timeIntervalSince(first.at)
        // stale: nothing moved for a while
        guard secs >= 0.5, now.timeIntervalSince(last.at) < Self.window else { return nil }
        return Double(last.bytes - min(first.bytes, last.bytes)) / secs
    }

    public func secondsLeft(at now: Date = .now) -> Double? {
        guard totalBytes > 0, let s = speed(at: now), s > 0 else { return nil }
        return Double(totalBytes - min(bytesDone, totalBytes)) / s
    }

    /// "12 MB of 40 MB · 25.3 MB/s · 2 s left"
    public func detail(at now: Date = .now) -> String {
        guard totalBytes > 0 else { return "" }
        var parts = ["\(bytes(bytesDone)) of \(bytes(totalBytes))"]
        if let s = speed(at: now) { parts.append(String(format: "%.1f MB/s", s / 1e6)) }
        if let left = secondsLeft(at: now), bytesDone < totalBytes { parts.append(timeLeft(left)) }
        return parts.joined(separator: " · ")
    }
}

/// "5 s left", "3 min left", "about 2 h left".
public func timeLeft(_ seconds: Double) -> String {
    let s = Int(seconds.rounded(.up))
    if s < 60 { return "\(max(s, 1)) s left" }
    if s < 3600 { return "\((s + 59) / 60) min left" }
    return "about \((s + 1800) / 3600) h left"
}

private func bytes(_ n: UInt64) -> String { ByteCountFormatter.string(fromByteCount: Int64(clamping: n), countStyle: .file) }

/// A transfer id the core accepts: 32 lowercase hex chars (UUID without dashes), matching its events.
public func newTransferID() -> String {
    UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
}
