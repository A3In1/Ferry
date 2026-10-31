import AppKit
import FerryLogic

/// "Share cursor with <phone>": pushing the pointer past the chosen screen edge hides and freezes the
/// Mac cursor and drives the phone's overlay cursor (CURSOR/SCROLL/TEXT/KEY) until it comes back or
/// Escape. An event tap reads the raw deltas and swallows input while crossed.
@MainActor final class Crossing {
    let peerId: String
    let edge: ScreenEdge
    private let model: AppModel
    private var tap: CFMachPort?
    private var own: ScreenSession? // control-only, used when no viewer window is open
    private var cursor: VirtualCursor?
    private var screen = CGRect.zero
    private var pressed = false
    private var retryAfter = Date.distantPast
    private var previousApp: NSRunningApplication?

    /// nil when the tap can't be created (no Accessibility permission).
    init?(model: AppModel, peerId: String, edge: ScreenEdge) {
        self.model = model
        self.peerId = peerId
        self.edge = edge
        let types: [CGEventType] = [.mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged, .rightMouseDown, .rightMouseUp,
                                    .rightMouseDragged, .otherMouseDown, .otherMouseUp, .otherMouseDragged, .scrollWheel, .keyDown, .keyUp]
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
            eventsOfInterest: types.reduce(0) { $0 | 1 << $1.rawValue },
            callback: { _, type, event, me in
                // The source is on the main run loop, so this runs on the main thread.
                let crossing = Unmanaged<Crossing>.fromOpaque(me!).takeUnretainedValue()
                let swallow = MainActor.assumeIsolated { crossing.handle(type, event) }
                return swallow ? nil : Unmanaged.passUnretained(event)
            },
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else { return nil }
        self.tap = tap
        CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, tap, 0), .commonModes)
    }

    /// Must run before the last reference goes (the tap holds an unretained pointer to self).
    func stop() {
        leave()
        if let tap { CFMachPortInvalidate(tap) }
        tap = nil
        own?.finish()
        own = nil
    }

    /// The viewer's stream if one is open (one stream per phone), else our control-only one.
    private var session: ScreenSession? {
        if let v = model.viewers[peerId]?.session, v.isOpen {
            if cursor == nil { own?.finish(); own = nil }
            return v
        }
        return own?.isOpen == true ? own : nil
    }

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Bool {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            leave() // never leave the cursor hidden and frozen without a working tap
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return false
        }
        guard var c = cursor else {
            if type == .mouseMoved { checkEdge(event) }
            return false
        }
        guard let s = session else { leave(); return false }
        let dx = event.getDoubleValueField(.mouseEventDeltaX), dy = event.getDoubleValueField(.mouseEventDeltaY)
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            guard c.move(dx: dx, dy: dy) else { leave(); return true }
            cursor = c
            s.send(Fsp.cursor(.move, c.normalized.x, c.normalized.y))
        case .leftMouseDown, .leftMouseUp:
            pressed = type == .leftMouseDown
            s.send(Fsp.cursor(pressed ? .down : .up, c.normalized.x, c.normalized.y))
        case .scrollWheel:
            guard let e = NSEvent(cgEvent: event) else { break }
            let (wx, wy) = wheelUnits(e)
            s.send(Fsp.scroll(c.normalized.x, c.normalized.y, dx: wx, dy: wy))
        case .keyDown:
            let code = UInt16(event.getIntegerValueField(.keyboardEventKeycode))
            if code == 53 { leave(); break } // Escape
            guard event.flags.isDisjoint(with: [.maskCommand, .maskControl]),
                  let m = Fsp.keyPress(keyCode: code, characters: NSEvent(cgEvent: event)?.characters) else { break }
            s.send(m)
        default: break // right/other buttons, key up: swallowed
        }
        return true
    }

    private func checkEdge(_ event: CGEvent) {
        let p = event.location
        guard let bounds = display(at: p),
              pushesEdge(edge, x: p.x, dx: event.getDoubleValueField(.mouseEventDeltaX), screenMinX: bounds.minX, screenMaxX: bounds.maxX),
              display(at: CGPoint(x: edge == .right ? bounds.maxX + 1 : bounds.minX - 1, y: p.y)) == nil // another screen there
        else { return }
        guard let s = session else { return Date.now >= retryAfter ? openControl() : () }
        guard let info = s.info, s.canControl else { return } // waiting for INFO, or control is off on the phone
        // shortcut: the size is fixed at entry, so a rotation while crossed keeps the old speed; re-init on INFO if it matters.
        let c = VirtualCursor(width: info.width, height: info.height, edge: edge, macScreenHeight: bounds.height,
                              entryY: (p.y - bounds.minY) / bounds.height)
        cursor = c
        screen = bounds
        // shortcut: CGDisplayHideCursor only sticks for the active app, so Ferry takes focus while crossed
        // (keys are swallowed by the tap anyway); upgrade if the focus flip is noticeable.
        previousApp = NSWorkspace.shared.frontmostApplication
        NSApp.activate()
        CGDisplayHideCursor(CGMainDisplayID())
        CGAssociateMouseAndMouseCursorPosition(0)
        s.send(Fsp.cursor(.move, c.normalized.x, c.normalized.y))
    }

    /// Back to the Mac: pointer to the edge at the same height, cursor shown, phone cursor hidden.
    private func leave() {
        guard let c = cursor else { return }
        cursor = nil
        if pressed { session?.send(Fsp.cursor(.up, c.normalized.x, c.normalized.y)) }
        pressed = false
        session?.send(Fsp.cursor(.hide, c.normalized.x, c.normalized.y))
        let y = screen.minY + Double(c.normalized.y) * screen.height
        CGWarpMouseCursorPosition(CGPoint(x: edge == .right ? screen.maxX - 2 : screen.minX + 1, y: min(y, screen.maxY - 1)))
        CGAssociateMouseAndMouseCursorPosition(1)
        CGDisplayShowCursor(CGMainDisplayID())
        previousApp?.activate()
        previousApp = nil
    }

    private func openControl() {
        guard let node = model.node else { return }
        let s = ScreenSession(node: node, peerId: peerId, video: false)
        s.onMessage = { [weak self] m in
            guard let self, case let .end(reason, detail) = m else { return }
            if session == nil { leave() }
            own = nil
            if reason != 0 { retryAfter = .now + 10 }
            NSLog("Ferry: cursor stream ended (\(reason)) \(detail)")
            if reason == 3 {
                model.cursorPeer = nil
                model.showAlert("Turn on Ferry control on the phone",
                                "Cursor sharing needs Ferry's control service: on the phone, Settings → Accessibility → Ferry.")
            }
        }
        own = s
    }

    private func display(at p: CGPoint) -> CGRect? {
        var id = CGDirectDisplayID(), n: UInt32 = 0
        CGGetDisplaysWithPoint(p, 1, &id, &n)
        return n > 0 ? CGDisplayBounds(id) : nil
    }
}

extension AppModel {
    /// The phone the cursor crosses to (menu "Share cursor with <phone>"), nil = off (default).
    var cursorPeer: String? {
        get { UserDefaults.standard.string(forKey: "cursorPeer") }
        set {
            UserDefaults.standard.set(newValue, forKey: "cursorPeer")
            updateCrossing()
        }
    }

    var cursorEdge: ScreenEdge {
        get { ScreenEdge(rawValue: UserDefaults.standard.string(forKey: "cursorEdge") ?? "") ?? .right }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: "cursorEdge")
            updateCrossing()
        }
    }

    /// Idempotent; also from tick (a forgotten phone turns it off).
    func updateCrossing() {
        let want = node == nil ? nil : cursorPeer.flatMap { id in peers.contains { $0.id == id } ? id : nil }
        if crossing?.peerId == want, crossing?.edge == cursorEdge { return }
        crossing?.stop()
        crossing = nil
        guard let want else { return }
        crossing = Crossing(model: self, peerId: want, edge: cursorEdge)
        if crossing == nil {
            UserDefaults.standard.removeObject(forKey: "cursorPeer")
            showAlert("Ferry can't share the cursor", "Allow Ferry in System Settings → Privacy & Security → Accessibility")
        }
    }
}
