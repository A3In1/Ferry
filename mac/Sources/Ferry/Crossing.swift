import AppKit
import FerryLogic

/// "Share Cursor": pushing the pointer past the chosen screen edge hides and freezes the
/// Mac cursor and drives the phone's overlay cursor (CURSOR/SCROLL/TEXT/KEY) until it comes back or
/// Escape. A listen-only tap watches mouse moves for the push; a filtering tap, enabled only while
/// crossed (so Ferry isn't in the path of every key and click otherwise), reads raw deltas and swallows input.
@MainActor final class Crossing {
    let peerId: String
    let edge: ScreenEdge
    private let model: AppModel
    private var tap: CFMachPort?
    private var watch: CFMachPort?
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
        // The sources are on the main run loop, so the callbacks run on the main thread.
        guard let tap = addTap(.defaultTap, .headInsertEventTap, types, { _, type, event, me in
            let crossing = Unmanaged<Crossing>.fromOpaque(me!).takeUnretainedValue()
            let swallow = MainActor.assumeIsolated { crossing.handle(type, event) }
            return swallow ? nil : Unmanaged.passUnretained(event)
        }) else { return nil }
        CGEvent.tapEnable(tap: tap, enable: false)
        self.tap = tap
        // After the filtering tap, so it never sees what that one swallowed.
        guard let watch = addTap(.listenOnly, .tailAppendEventTap, [.mouseMoved], { _, type, event, me in
            let crossing = Unmanaged<Crossing>.fromOpaque(me!).takeUnretainedValue()
            MainActor.assumeIsolated { crossing.watched(type, event) }
            return Unmanaged.passUnretained(event)
        }) else {
            CFMachPortInvalidate(tap)
            return nil
        }
        self.watch = watch
    }

    private func addTap(_ options: CGEventTapOptions, _ place: CGEventTapPlacement, _ types: [CGEventType],
                        _ callback: CGEventTapCallBack) -> CFMachPort? {
        guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: place, options: options,
                                          eventsOfInterest: types.reduce(0) { $0 | 1 << $1.rawValue }, callback: callback,
                                          userInfo: Unmanaged.passUnretained(self).toOpaque()) else { return nil }
        CFRunLoopAddSource(CFRunLoopGetMain(), CFMachPortCreateRunLoopSource(nil, tap, 0), .commonModes)
        return tap
    }

    /// Must run before the last reference goes (the taps hold an unretained pointer to self).
    func stop() {
        leave()
        for t in [tap, watch].compactMap(\.self) { CFMachPortInvalidate(t) }
        tap = nil
        watch = nil
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

    private func watched(_ type: CGEventType, _ event: CGEvent) {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let watch { CGEvent.tapEnable(tap: watch, enable: true) }
        } else if type == .mouseMoved, cursor == nil {
            checkEdge(event)
        }
    }

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Bool {
        // Never leave the cursor hidden and frozen without a working tap. leave() keeps it off until the next crossing.
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput { leave(); return false }
        guard var c = cursor else { return false }
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
        let p = event.location, dx = event.getDoubleValueField(.mouseEventDeltaX)
        guard edge == .right ? dx > 0 : dx < 0, // cheap test first: this sees every mouse move
              let bounds = display(at: p),
              pushesEdge(edge, x: p.x, dx: dx, screenMinX: bounds.minX, screenMaxX: bounds.maxX),
              display(at: CGPoint(x: edge == .right ? bounds.maxX + 1 : bounds.minX - 1, y: p.y)) == nil // another screen there
        else { return }
        guard let s = session else { return Date.now >= retryAfter ? openControl() : () }
        guard let info = s.info, s.canControl else { return } // waiting for INFO, or control is off on the phone
        // shortcut: the size is fixed at entry, so a rotation while crossed keeps the old speed; re-init on INFO if it matters.
        let c = VirtualCursor(width: info.width, height: info.height, edge: edge, macScreenHeight: bounds.height,
                              entryY: (p.y - bounds.minY) / bounds.height)
        cursor = c
        screen = bounds
        if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
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
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
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
            logger.info("cursor stream ended (\(reason)) \(detail, privacy: .public)")
            if reason == 3 {
                model.cursorPeer = nil
                model.showAlert("Turn on Ferry control on the phone",
                                "To share the cursor, turn on Ferry control on the phone in Settings → Accessibility → Ferry, "
                                    + "then choose Share Cursor again.")
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
    /// The phone the cursor crosses to (menu "Share Cursor"), nil = off (default).
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
        let want = node == nil ? nil : cursorPeer.flatMap { peer($0)?.id }
        if crossing?.peerId == want, want == nil || crossing?.edge == cursorEdge { return }
        crossing?.stop()
        crossing = nil
        guard let want else { return }
        crossing = Crossing(model: self, peerId: want, edge: cursorEdge)
        if crossing == nil {
            UserDefaults.standard.removeObject(forKey: "cursorPeer")
            if confirm("Allow Ferry to share the cursor", "Turn on Ferry in System Settings → Privacy & Security → Accessibility, "
                           + "then choose Share Cursor again.", "Open System Settings") {
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
            }
        }
    }
}
