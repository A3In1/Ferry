import CoreGraphics

/// The Mac screen edge the phone sits beyond for cursor crossing.
public enum ScreenEdge: String, CaseIterable, Sendable { case left, right }

/// True when the pointer is pinned at `edge` of its screen and still pushing into it.
/// x is in global CG coordinates; the cursor stops at maxX - 1 on the right.
public func pushesEdge(_ edge: ScreenEdge, x: Double, dx: Double, screenMinX: Double, screenMaxX: Double) -> Bool {
    switch edge {
    case .right: dx > 0 && x >= screenMaxX - 1
    case .left: dx < 0 && x <= screenMinX
    }
}

/// The phone pointer while crossed, in phone px (INFO width/height). Mac deltas are scaled so the
/// phone's long side spans the Mac screen's height.
public struct VirtualCursor: Equatable, Sendable {
    public let width, height, scale: Double
    public let edge: ScreenEdge
    public private(set) var x, y: Double

    /// `entryY`: 0...1 down the Mac screen where the pointer left it.
    public init(width: Int, height: Int, edge: ScreenEdge, macScreenHeight: Double, entryY: Double) {
        self.width = Double(width)
        self.height = Double(height)
        self.edge = edge
        scale = Double(max(width, height)) / max(macScreenHeight, 1)
        x = edge == .right ? 0 : self.width
        y = min(max(entryY, 0), 1) * self.height
    }

    /// Applies a Mac mouse delta (points). False = it went back past the phone's Mac-side edge.
    public mutating func move(dx: Double, dy: Double) -> Bool {
        x += dx * scale
        y = min(max(y + dy * scale, 0), height)
        if edge == .right ? x < 0 : x > width { return false }
        x = min(max(x, 0), width)
        return true
    }

    public var normalized: (x: Float, y: Float) { (Float(x / width), Float(y / height)) }
}

/// A point in a view as 0...1 of the video drawn aspect-fit inside `bounds`, y down (phone order).
/// nil when outside the video unless `clamp`.
public func videoPoint(_ p: CGPoint, video: CGSize, bounds: CGRect, flipped: Bool, clamp: Bool = false) -> CGPoint? {
    guard video.width > 0, video.height > 0, bounds.width > 0, bounds.height > 0 else { return nil }
    let s = min(bounds.width / video.width, bounds.height / video.height)
    let w = video.width * s, h = video.height * s
    let r = CGRect(x: bounds.midX - w / 2, y: bounds.midY - h / 2, width: w, height: h)
    var x = (p.x - r.minX) / w, y = (p.y - r.minY) / h
    if !flipped { y = 1 - y }
    if clamp { return CGPoint(x: min(max(x, 0), 1), y: min(max(y, 0), 1)) }
    return (0...1).contains(x) && (0...1).contains(y) ? CGPoint(x: x, y: y) : nil
}
