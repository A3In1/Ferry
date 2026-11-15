import Foundation

// USB fast path for Browse Files downloads: plain `adb pull` of the phone's shared storage.

/// Where the phone's shared storage root ("" in Ferry's phone paths) lives for adb (primary user).
public let adbStorageRoot = "/storage/emulated/0"

/// A Ferry phone path ("DCIM/Camera") as the path `adb pull` takes, or nil if it isn't a valid phone path
/// (or is the root itself: pulling all of shared storage isn't a download).
public func adbDevicePath(_ phonePath: String) -> String? {
    guard let p = normalizedPhonePath(phonePath), !p.isEmpty else { return nil }
    return "\(adbStorageRoot)/\(p)"
}

/// One progress line of `adb pull` run on a terminal:
/// modern adb  `[ 45%] /storage/emulated/0/DCIM/a.jpg: 80%`  (overall %, then this file's %),
/// older adb   `[ 45%] /storage/emulated/0/DCIM/a.jpg`.
public struct AdbProgress: Equatable, Sendable {
    public var overall: Int? // nil when adb prints "?" (size not known yet)
    public var path: String
    public var filePercent: Int?

    public init(overall: Int?, path: String, filePercent: Int?) {
        self.overall = overall
        self.path = path
        self.filePercent = filePercent
    }

    /// `line` as adb printed it; terminal escapes ("\u{1B}[K") are ignored.
    public static func parse(_ line: String) -> AdbProgress? {
        let s = stripEscapes(line).trimmingCharacters(in: .whitespacesAndNewlines)
        guard s.hasPrefix("["), let close = s.firstIndex(of: "]") else { return nil }
        let head = s[s.index(after: s.startIndex)..<close].trimmingCharacters(in: .whitespaces)
        guard head.hasSuffix("%") || head == "?" else { return nil }
        let overall = head == "?" ? nil : Int(head.dropLast())
        if head != "?", overall == nil { return nil }
        var rest = s[s.index(after: close)...].trimmingCharacters(in: .whitespaces)
        guard !rest.isEmpty else { return nil }
        var file: Int?
        // ": 80%" or ": 12345/?" after the path (a path may itself contain ": ")
        if let colon = rest.range(of: ": ", options: .backwards) {
            let tail = rest[colon.upperBound...]
            if tail.hasSuffix("%"), let n = Int(tail.dropLast()) {
                file = n
                rest = String(rest[..<colon.lowerBound])
            } else if tail.hasSuffix("/?"), Int(tail.dropLast(2)) != nil {
                rest = String(rest[..<colon.lowerBound])
            }
        }
        return AdbProgress(overall: overall.map { min(max($0, 0), 100) }, path: rest, filePercent: file)
    }
}

/// Removes ANSI CSI sequences (adb's "clear to end of line" on a terminal).
public func stripEscapes(_ s: String) -> String {
    var out = ""
    var it = s.makeIterator()
    while let c = it.next() {
        guard c == "\u{1B}" else { out.append(c); continue }
        guard it.next() == "[" else { continue }
        while let d = it.next(), !(d.isLetter) {}
    }
    return out
}

/// Why `adb pull` failed, from its output, as a short reason; nil if nothing recognisable.
/// Any failure falls back to the Ferry transfer; this is for the log.
public func adbPullError(_ output: String) -> String? {
    let lines = output.split(whereSeparator: { $0 == "\n" || $0 == "\r" }).map { stripEscapes(String($0)) }
    return lines.last { $0.contains("error:") || $0.contains("Permission denied") || $0.contains("No such file") || $0.contains("unauthorized") }
        .map { $0.trimmingCharacters(in: .whitespaces) }
}

/// `name`, else `name (1)`, `name (2)`… (before the extension, like Ferry's own downloads) — the first `taken` says is free.
public func freeName(_ name: String, isDirectory: Bool, taken: (String) -> Bool) -> String {
    if !taken(name) { return name }
    let dot = isDirectory ? nil : name.lastIndex(of: ".").flatMap { $0 == name.startIndex ? nil : $0 }
    let (stem, ext) = dot.map { (String(name[..<$0]), String(name[$0...])) } ?? (name, "")
    for n in 1..<10_000 {
        let candidate = "\(stem) (\(n))\(ext)"
        if !taken(candidate) { return candidate }
    }
    return "\(stem) (\(UUID().uuidString.prefix(8)))\(ext)"
}

/// What a USB download's row shows: adb's own percentages, plus bytes measured on disk for speed.
public struct UsbPullState: Equatable, Sendable {
    public let folder: Bool
    public let knownSize: UInt64? // a single file's size from the phone's listing
    public private(set) var overall: Int?
    public private(set) var file: String?
    public private(set) var filesSeen = 0
    private var disk = TransferProgress(names: [""])

    public init(folder: Bool, knownSize: UInt64?) {
        self.folder = folder
        self.knownSize = knownSize
        disk.totalBytes = knownSize ?? 0
    }

    public mutating func update(_ p: AdbProgress) {
        if let o = p.overall { overall = o }
        if p.path != file {
            file = p.path
            filesSeen += 1
        }
    }

    public mutating func onDisk(_ bytes: UInt64, at now: Date = .now) {
        disk.progress(index: 0, done: bytes, total: max(knownSize ?? .max, bytes), at: now)
    }

    public var bytes: UInt64 { disk.bytesDone }

    /// nil until adb has said anything (indeterminate bar).
    public var fraction: Double? {
        if let overall { return Double(overall) / 100 }
        if let knownSize, knownSize > 0 { return min(1, Double(bytes) / Double(knownSize)) }
        return nil
    }

    /// "File 12: IMG_1.jpg" for a folder (adb doesn't say how many there are).
    public var fileLine: String? {
        guard folder, let file else { return nil }
        return "File \(filesSeen): \(file.split(separator: "/").last.map(String.init) ?? file)"
    }

    /// "34 MB · 38.1 MB/s · 2 s left · over USB"
    public func detail(at now: Date = .now) -> String {
        var parts = [ByteCountFormatter.string(fromByteCount: Int64(clamping: bytes), countStyle: .file)
            + (knownSize.map { " of " + ByteCountFormatter.string(fromByteCount: Int64(clamping: $0), countStyle: .file) } ?? "")]
        if let s = disk.speed(at: now), s > 0 {
            parts.append(String(format: "%.1f MB/s", s / 1e6))
            if let f = fraction, f > 0.01, f < 1 {
                parts.append(timeLeft(Double(bytes) * (1 - f) / f / s))
            }
        }
        parts.append("over USB")
        return parts.joined(separator: " · ")
    }
}
