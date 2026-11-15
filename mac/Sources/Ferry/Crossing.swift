import AppKit
import ApplicationServices
import FerryCore
import FerryLogic

/// "Share Cursor": pushing the pointer past the chosen screen edge hides and freezes the
/// Mac cursor and drives the phone's overlay cursor (CURSOR/SCROLL/TEXT/KEY) until it comes back or
/// Escape. A listen-only tap watches mouse moves for the push; a filtering tap, enabled only while
/// crossed (so Ferry isn't in the path of every key and click otherwise), reads raw deltas and swallows input.
/// It can't stay stuck swallowing: a disabled tap, a lost session, sleep, lock, a user switch or a display
/// change all bring the pointer back.
@MainActor final class Crossing {
    let peerId: String
    let edge: ScreenEdge
    private let model: AppModel
    private var tap: CFMachPort?
    private var watch: CFMachPort?
    private var sources: [CFRunLoopSource] = []
    private var observers: [(NotificationCenter, NSObjectProtocol)] = []
    private var watchdog: Timer?
    private var push = EdgePush()
    // Moves are merged and sent at most at 120 Hz (the phone redraws once per frame anyway).
    private var pacer = Pacer(interval: 1.0 / 120)
    private var unsent = false
    private var flushQueued = false
    private var own: ScreenSession?
    private var cursor: VirtualCursor?
    private var screen = CGRect.zero
    private var pressed = false
    private var retryAfter = Date.distantPast
    private var previousApp: NSRunningApplication?
    /// A push got through the dead zone while the control stream was still opening: cross once it's ready.
    private var armedAt = Date.distantPast
    private var lastRefusal: (why: String, at: Date) = ("", .distantPast)
    private var lastUsed = Date.now
    private var keepalive: Timer?
    private var movesSent = 0, movesSince = Date.now
    /// Why crossing can't work right now, for the menu (nil = fine).
    private(set) var problem: String?

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
        checkPermission()
        // Anything that can strand the pointer on the phone brings it back.
        let ws = NSWorkspace.shared.notificationCenter
        for (center, name) in [(ws, NSWorkspace.willSleepNotification), (ws, NSWorkspace.screensDidSleepNotification),
                               (ws, NSWorkspace.sessionDidResignActiveNotification),
                               (NotificationCenter.default, NSApplication.didChangeScreenParametersNotification),
                               (DistributedNotificationCenter.default(), Notification.Name("com.apple.screenIsLocked"))] {
            observers.append((center, center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.leave("\(name.rawValue)") }
            }))
        }
    }

    private func addTap(_ options: CGEventTapOptions, _ place: CGEventTapPlacement, _ types: [CGEventType],
                        _ callback: CGEventTapCallBack) -> CFMachPort? {
        guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: place, options: options,
                                          eventsOfInterest: types.reduce(0) { $0 | 1 << $1.rawValue }, callback: callback,
                                          userInfo: Unmanaged.passUnretained(self).toOpaque()) else { return nil }
        guard let source = CFMachPortCreateRunLoopSource(nil, tap, 0) else {
            CFMachPortInvalidate(tap)
            return nil
        }
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        sources.append(source)
        return tap
    }

    /// Must run before the last reference goes (the taps hold an unretained pointer to self).
    func stop() {
        leave("Share Cursor turned off")
        keepalive?.invalidate()
        keepalive = nil
        for t in [tap, watch].compactMap(\.self) { CFMachPortInvalidate(t) }
        for s in sources {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), s, .commonModes)
            CFRunLoopSourceInvalidate(s)
        }
        sources = []
        tap = nil
        watch = nil
        for (center, o) in observers { center.removeObserver(o) }
        observers = []
        own?.finish()
        own = nil
    }

    private var session: ScreenSession? { own?.isOpen == true ? own : nil }

    private func watched(_ type: CGEventType, _ event: CGEvent) {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let watch { CGEvent.tapEnable(tap: watch, enable: true) }
        } else if type == .mouseMoved, cursor == nil {
            checkEdge(event)
        }
    }

    private func handle(_ type: CGEventType, _ event: CGEvent) -> Bool {
        // Never leave the cursor hidden and frozen without a working tap. leave() keeps it off until the next crossing.
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput { leave("event tap disabled by macOS"); return false }
        guard var c = cursor else { return false }
        guard let s = session else { leave("the phone's stream closed"); return false }
        let dx = event.getDoubleValueField(.mouseEventDeltaX), dy = event.getDoubleValueField(.mouseEventDeltaY)
        switch type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            guard c.move(dx: dx, dy: dy) else { leave("moved back"); return true }
            cursor = c
            moved()
        case .leftMouseDown, .leftMouseUp:
            flushMove()
            pressed = type == .leftMouseDown
            s.send(Fsp.cursor(pressed ? .down : .up, c.normalized.x, c.normalized.y))
        case .scrollWheel:
            guard let e = NSEvent(cgEvent: event), let w = Wheel(e) else { break }
            flushMove()
            s.send(Fsp.scroll(c.normalized.x, c.normalized.y, dx: w.dx, dy: w.dy, flags: w.flags))
        case .keyDown:
            let code = UInt16(event.getIntegerValueField(.keyboardEventKeycode))
            if code == 53 { leave("Escape"); break }
            guard let e = NSEvent(cgEvent: event), let m = keyMessage(e) else { break }
            s.send(m)
        default: break // right/other buttons, key up: swallowed
        }
        return true
    }

    private func checkEdge(_ event: CGEvent) {
        let p = event.location, dx = event.getDoubleValueField(.mouseEventDeltaX)
        guard edge == .right ? dx > 0 : dx < 0, // cheap test first: this sees every mouse move
              let bounds = display(at: p),
              pushesEdge(edge, x: p.x, dx: dx, screenMinX: bounds.minX, screenMaxX: bounds.maxX)
        else { return push.reset() }
        guard display(at: CGPoint(x: edge == .right ? bounds.maxX + 1 : bounds.minX - 1, y: p.y)) == nil else {
            push.reset()
            return refuse("another display is past the \(edge.rawValue) edge; choose the other edge in Settings → Cursor Crosses At",
                          sticky: true)
        }
        guard push.push(dx, at: ProcessInfo.processInfo.systemUptime) else { return } // the dead zone
        guard let s = session else {
            armedAt = .now
            if Date.now < retryAfter { return refuse("the last stream to the phone failed; retrying in a few seconds") }
            return openControl()
        }
        guard s.info != nil else {
            armedAt = .now
            return refuse("waiting for the phone to answer")
        }
        cross(at: p, bounds: bounds, s)
    }

    /// Logs why a push didn't cross (each reason at most every 3 s). `sticky`: shown in the menu until a crossing works.
    private func refuse(_ why: String, sticky: Bool = false) {
        if sticky { problem = why }
        guard lastRefusal.why != why || Date.now.timeIntervalSince(lastRefusal.at) > 3 else { return }
        lastRefusal = (why, .now)
        logger.notice("cursor: not crossing: \(why, privacy: .public)")
    }

    private func cross(at p: CGPoint, bounds: CGRect, _ s: ScreenSession) {
        guard cursor == nil, let info = s.info else { return }
        guard s.canControl else {
            return refuse("Ferry control is off on the phone (Settings → Accessibility → Ferry)", sticky: true)
        }
        guard AXIsProcessTrusted() else { return checkPermission() }
        armedAt = .distantPast
        problem = nil
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
        s.sendLatest(Fsp.cursor(.move, c.normalized.x, c.normalized.y), slot: ScreenSession.cursorSlot)
        pacer.sent(at: ProcessInfo.processInfo.systemUptime)
        movesSent = 0
        movesSince = .now
        logger.notice("cursor: crossed to the phone (\(info.width)x\(info.height)) at y \(String(format: "%.2f", c.normalized.y), privacy: .public)")
        // Catches what no event reports: the tap silently disabled, or the stream gone while the mouse rests.
        watchdog = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.check() }
        }
    }

    /// Accessibility (for the event taps) is per build: after an update macOS may keep the switch on but
    /// stop trusting the app. Say so in the menu and the log rather than fail silently.
    private func checkPermission() {
        if AXIsProcessTrusted() {
            if problem?.hasPrefix("macOS") == true { problem = nil }
            return
        }
        refuse("macOS isn't letting Ferry see the pointer: in System Settings → Privacy & Security → Accessibility, "
            + "turn Ferry off and on again", sticky: true)
    }

    private func check() {
        guard cursor != nil else { return }
        if session == nil { return leave("the phone's stream closed") }
        if !(tap.map { CGEvent.tapIsEnabled(tap: $0) } ?? false) { leave("event tap disabled") }
    }

    /// The cursor moved: send now, or once the pacer allows (merging the moves in between).
    private func moved() {
        unsent = true
        guard !flushQueued else { return }
        let wait = pacer.wait(now: ProcessInfo.processInfo.systemUptime)
        if wait == 0 { return flushMove() }
        flushQueued = true
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
            MainActor.assumeIsolated {
                self?.flushQueued = false
                self?.flushMove()
            }
        }
    }

    /// Sends the latest position if it hasn't gone yet (also before a press or scroll, so they land there).
    private func flushMove() {
        guard unsent, let c = cursor, let s = session else { return }
        unsent = false
        pacer.sent(at: ProcessInfo.processInfo.systemUptime)
        // Latest wins: if the link is slow, a newer position replaces one still waiting instead of queueing.
        s.sendLatest(Fsp.cursor(.move, c.normalized.x, c.normalized.y), slot: ScreenSession.cursorSlot)
        movesSent += 1
        let t = Date.now.timeIntervalSince(movesSince)
        if t >= 2 {
            logger.debug("cursor: \(Int(Double(self.movesSent) / t)) moves/s sent")
            movesSent = 0
            movesSince = .now
        }
    }

    /// Back to the Mac: pointer to the edge at the same height, cursor shown, phone cursor hidden.
    private func leave(_ why: String) {
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        watchdog?.invalidate()
        watchdog = nil
        push.reset()
        guard let c = cursor else { return }
        flushMove()
        cursor = nil
        lastUsed = .now
        logger.notice("cursor: back on the Mac (\(why, privacy: .public))")
        unsent = false
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

    /// The control-only stream, after the Mac owner confirms (Touch ID / password, remembered a while).
    /// Kept open (with a keepalive, so an idle link isn't timed out) until 10 minutes without a crossing.
    private func openControl() {
        guard let node = model.node, own == nil else { return }
        retryAfter = .now + 2 // one attempt at a time, including the password prompt
        let name = model.peer(peerId)?.name ?? "your phone"
        let asked = Date.now
        MacAuth.confirm("control \(name) with the cursor") { [weak self] ok in
            guard let self, self.tap != nil else { return }
            guard ok else {
                self.retryAfter = .now + 10
                return self.refuse("the Mac password wasn't confirmed")
            }
            // After a prompt the pointer has moved on (the dialog took focus): push again to cross.
            if Date.now.timeIntervalSince(asked) > 0.5 { self.armedAt = .distantPast }
            self.open(node)
        }
    }

    private func open(_ node: FerryNode) {
        logger.notice("cursor: opening the control stream to the phone")
        let s = ScreenSession(node: node, peerId: peerId)
        s.onMessage = { [weak self] m in
            guard let self else { return }
            switch m {
            case .info:
                logger.notice("cursor: phone ready (control \(s.canControl ? "on" : "off", privacy: .public))")
                // Still pushing at the edge from before the stream was ready: cross now.
                if cursor == nil, Date.now.timeIntervalSince(armedAt) < 2, let p = CGEvent(source: nil)?.location,
                   let bounds = display(at: p), pushesEdge(edge, x: p.x, dx: edge == .right ? 1 : -1, screenMinX: bounds.minX, screenMaxX: bounds.maxX) {
                    cross(at: p, bounds: bounds, s)
                }
            case let .end(reason, detail):
                if session == nil { leave("the phone's stream ended") }
                own = nil
                keepalive?.invalidate()
                keepalive = nil
                if reason != 0 { retryAfter = .now + 5 }
                logger.notice("cursor: control stream ended (\(reason)) \(detail, privacy: .public)")
                if reason == 3 {
                    model.cursorPeer = nil
                    model.showAlert("Turn on Ferry control on the phone",
                                    "To share the cursor, turn on Ferry control on the phone in Settings → Accessibility → Ferry, "
                                        + "then choose Share Cursor again.")
                }
            }
        }
        own = s
        retryAfter = .distantPast
        lastUsed = .now
        keepalive?.invalidate()
        keepalive = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tickKeepalive() }
        }
    }

    private func tickKeepalive() {
        checkPermission()
        guard let own, own.isOpen else { return }
        if cursor == nil, Date.now.timeIntervalSince(lastUsed) > 600 {
            logger.notice("cursor: closing the idle control stream")
            own.finish()
            self.own = nil
            keepalive?.invalidate()
            keepalive = nil
            return
        }
        own.send(Fsp.keepalive)
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
            logger.notice("cursor: macOS refused the event tap (Ferry isn't trusted for Accessibility)")
            if confirm("Allow Ferry to share the cursor", "Turn on Ferry in System Settings → Privacy & Security → Accessibility, then choose "
                           + "Share Cursor again. If Ferry already looks on, that's from an older build: select it, remove it with −, and add it again.",
                       "Open System Settings") {
                AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary) // lists Ferry there
                NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
            }
        }
    }
}
