import Foundation

// Phone file paths (Phase 6) are relative to the phone's shared storage root ("" = root) and "/"-separated.
// They're never absolute and never contain "." or "..": anything that would need those is refused (nil).

/// A path from anywhere (the phone's FsList echo, our own state) in canonical form, or nil if it has "." / "..".
public func normalizedPhonePath(_ path: String) -> String? {
    let parts = path.split(separator: "/").map(String.init)
    guard !parts.contains(where: { $0 == "." || $0 == ".." || $0.contains("\0") }) else { return nil }
    return parts.joined(separator: "/")
}

/// `name` inside folder `dir`, or nil if `name` isn't a plain file name (empty, ".", "..", has "/").
public func phonePath(_ dir: String, _ name: String) -> String? {
    guard !name.isEmpty, name != ".", name != "..", !name.contains("/"), !name.contains("\0"),
          let dir = normalizedPhonePath(dir) else { return nil }
    return dir.isEmpty ? name : "\(dir)/\(name)"
}

/// The folder above `path`; the root is its own parent.
public func parentPhonePath(_ path: String) -> String {
    (normalizedPhonePath(path) ?? "").split(separator: "/").dropLast().joined(separator: "/")
}

public struct Crumb: Equatable, Sendable {
    public let name, path: String
    public init(name: String, path: String) { self.name = name; self.path = path }
}

/// Path bar segments below the root: "DCIM/Camera" → DCIM (DCIM), Camera (DCIM/Camera).
public func breadcrumbs(_ path: String) -> [Crumb] {
    var at = ""
    return (normalizedPhonePath(path) ?? "").split(separator: "/").map {
        at = at.isEmpty ? String($0) : "\(at)/\($0)"
        return Crumb(name: String($0), path: at)
    }
}

/// Listing order: folders first, then Finder-style name order ("img2" before "img10").
public func folderFirst(_ a: (dir: Bool, name: String), _ b: (dir: Bool, name: String)) -> Bool {
    if a.dir != b.dir { return a.dir }
    return a.name.localizedStandardCompare(b.name) == .orderedAscending
}
