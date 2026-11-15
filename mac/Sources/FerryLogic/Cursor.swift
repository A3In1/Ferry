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

/// A small dead zone before crossing: the pointer has to keep pushing into the edge for `distance`
/// points (without a pause longer than `window`), so throwing it at a scroll bar doesn't cross.
public struct EdgePush: Sendable {
    public let distance: Double, window: Double
    private var pushed = 0.0, last = -Double.infinity

    public init(distance: Double = 8, window: Double = 0.4) {
        self.distance = distance
        self.window = window
    }

    /// One push of `d` points at time `t` (seconds); true = cross now.
    public mutating func push(_ d: Double, at t: Double) -> Bool {
        if t - last > window { pushed = 0 }
        last = t
        pushed += abs(d)
        guard pushed >= distance else { return false }
        pushed = 0
        return true
    }

    public mutating func reset() { pushed = 0 }
}

/// Rate limit for cursor updates: at most one per `interval`; moves in between are merged
/// (the cursor keeps sub-pixel positions, so nothing is lost).
public struct Pacer: Sendable {
    public let interval: Double
    private var last = -Double.infinity

    public init(interval: Double) { self.interval = interval }

    /// Seconds until the next send may go (0 = now).
    public func wait(now: Double) -> Double { max(0, last + interval - now) }

    public mutating func sent(at now: Double) { last = now }
}

/// The phone pointer while crossed, in phone px (INFO width/height). Mac deltas (already shaped by
/// the Mac's pointer acceleration) are scaled so the phone's long side spans the Mac screen's height,
/// and y maps proportionally both ways, so going over and straight back returns to the same height.
public struct VirtualCursor: Equatable, Sendable {
    public let width, height, scale: Double
    public let edge: ScreenEdge
    public private(set) var x, y: Double
    /// Mac points past the phone's Mac-side edge before it goes back (a small dead zone like `EdgePush`'s).
    public static let exitPush = 4.0

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
    /// Pushing back is kept (up to the dead zone) while the cursor sits at that edge.
    public mutating func move(dx: Double, dy: Double) -> Bool {
        let slack = Self.exitPush * scale
        x = min(max(x + dx * scale, -slack), width + slack)
        y = min(max(y + dy * scale, 0), height)
        if edge == .right ? x <= -slack : x >= width + slack { return false }
        // Past the far edge snaps back at once; past the Mac-side edge waits for the dead zone.
        x = edge == .right ? min(x, width) : max(x, 0)
        return true
    }

    public var normalized: (x: Float, y: Float) { (Float(min(max(x, 0), width) / width), Float(y / height)) }
}
