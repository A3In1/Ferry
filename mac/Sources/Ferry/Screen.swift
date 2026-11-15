import AppKit
import FerryCore
import FerryLogic
import LocalAuthentication

/// One FSP1 control stream to a phone (docs/screen-protocol.md), for Share Cursor. Writes go out in order.
/// The stream is read off the main thread; messages arrive on the main actor, and END (or EOF/error, as
/// END 0/4) comes exactly once.
@MainActor final class ScreenSession {
    let peerId: String
    private(set) var info: (width: Int, height: Int, caps: UInt8)?
    private(set) var isOpen = true
    var onMessage: (Fsp.Message) -> Void = { _ in }
    private let out = Outbox()
    /// Latest-wins slot in `out`.
    nonisolated static let cursorSlot = 0

    init(node: FerryNode, peerId: String) {
        self.peerId = peerId
        out.send(Fsp.magic + Fsp.start)
        Task { await run(node) }
    }

    var canControl: Bool { (info?.caps ?? 0) & 2 != 0 }

    func send(_ data: Data) { if isOpen { out.send(data) } }

    /// A message only the newest of which matters (cursor moves): replaces one still waiting to go out.
    func sendLatest(_ data: Data, slot: Int) { if isOpen { out.sendLatest(data, slot: slot) } }

    /// Ends our side; the phone then closes its side. No messages after this.
    func finish() {
        isOpen = false
        onMessage = { _ in }
        out.finish()
    }

    private func run(_ node: FerryNode) async {
        let stream: ScreenStream
        do { stream = try await node.openScreen(peerId: peerId) } catch { return end(4, "\(error)") }
        guard isOpen else { return await stream.finish() } // closed while connecting
        // Neither loop runs on the main actor, so a busy UI can't hold up the wire.
        let out = out
        Task.detached(priority: .userInitiated) {
            for await item in out.items {
                let data: Data?
                switch item {
                case let .data(d): data = d
                case let .latest(slot, gen): data = out.take(slot: slot, gen: gen)
                }
                guard let data else { continue }
                do { try await stream.write(data: data) } catch { break }
            }
            await stream.finish()
        }
        let (reason, detail) = await Task.detached(priority: .userInitiated) { () async -> (UInt8, String) in
            var parser = FspParser()
            do {
                while true {
                    let chunk = try await stream.read()
                    if chunk.isEmpty { return (0, "") }
                    for m in try parser.push(chunk) {
                        if case let .end(reason, detail) = m { return (reason, detail) }
                        await self.deliver(m)
                    }
                }
            } catch {
                return (4, "\(error)")
            }
        }.value
        end(reason, detail)
    }

    private func deliver(_ m: Fsp.Message) {
        guard isOpen else { return }
        if case let .info(w, h, caps) = m { info = (w, h, caps) }
        onMessage(m)
    }

    private func end(_ reason: UInt8, _ detail: String) {
        guard isOpen else { return }
        let handler = onMessage
        finish()
        handler(.end(reason: reason, detail: detail))
    }
}

/// "Require Mac Password for Share Cursor" (default on): Touch ID or the Mac password before cursor
/// control starts. Remembered for 5 minutes, forgotten when the Mac sleeps, locks or switches user.
@MainActor enum MacAuth {
    private static let key = "requireMacAuth"
    static let remember: TimeInterval = 5 * 60
    private static var until = Date.distantPast
    private static var waiting: [@MainActor (Bool) -> Void] = []
    private static var observers: [NSObjectProtocol] = []

    static var required: Bool {
        get { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: key)
            until = .distantPast
        }
    }

    /// `done(true)` at once when not required or confirmed recently; else after Touch ID / the password.
    /// One prompt at a time: callers arriving meanwhile share its answer. `reason` completes "Ferry is trying to …".
    static func confirm(_ reason: String, done: @escaping @MainActor (Bool) -> Void) {
        observe()
        guard required, Date.now >= until else { return done(true) }
        waiting.append(done)
        guard waiting.count == 1 else { return }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No way to ask (no password set on this Mac account): don't lock the owner out.
            logger.notice("mac auth unavailable: \(error?.localizedDescription ?? "", privacy: .public); allowing")
            return finish(true)
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, error in
            let why = error?.localizedDescription ?? ""
            Task { @MainActor in
                logger.notice("mac auth \(ok ? "confirmed" : "refused", privacy: .public) \(why, privacy: .public)")
                if ok { until = .now + remember }
                finish(ok)
            }
        }
    }

    private static func finish(_ ok: Bool) {
        let w = waiting
        waiting = []
        w.forEach { $0(ok) }
    }

    private static func observe() {
        guard observers.isEmpty else { return }
        let ws = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            observers.append(ws.addObserver(forName: name, object: nil, queue: .main) { _ in MainActor.assumeIsolated { until = .distantPast } })
        }
        observers.append(DistributedNotificationCenter.default().addObserver(forName: Notification.Name("com.apple.screenIsLocked"), object: nil,
                                                                             queue: .main) { _ in MainActor.assumeIsolated { until = .distantPast } })
    }
}

/// A scroll event as SCROLL: deltas in wheel lines (+dy = scroll up; trackpads report points, about 10
/// per line) as macOS delivers them, so the user's natural-scrolling setting carries over. Trackpad
/// deltas and momentum are continuous; the phone plays them as one finger drag and lifts it on end.
struct Wheel {
    let dx, dy: Float
    let flags: UInt8
    /// The first event of a scroll (or any wheel step).
    let starts: Bool

    /// nil for events with nothing to send (a trackpad touch that hasn't moved yet).
    init?(_ e: NSEvent) {
        let precise = e.hasPreciseScrollingDeltas, k: CGFloat = precise ? 0.1 : 1
        dx = Float(e.scrollingDeltaX * k)
        dy = Float(e.scrollingDeltaY * k)
        let over: NSEvent.Phase = [.ended, .cancelled]
        // A finger lift may be followed by momentum; the phone waits a moment before lifting on a phase end.
        let end = !e.momentumPhase.isDisjoint(with: over) || (e.momentumPhase.isEmpty && !e.phase.isDisjoint(with: over))
        flags = (precise ? Fsp.scrollContinuous : 0) | (end ? Fsp.scrollEnd : 0)
        starts = !precise || e.phase.contains(.began) || e.phase.contains(.mayBegin)
        if dx == 0, dy == 0, !end { return nil }
    }
}

/// KEY/TEXT for a key press; ⌃ combinations and ⌘ ones other than ⌘-arrows send nothing.
func keyMessage(_ e: NSEvent) -> Data? {
    let m = e.modifierFlags
    guard !m.contains(.control) else { return nil }
    return Fsp.keyPress(keyCode: e.keyCode, characters: e.characters, shift: m.contains(.shift), option: m.contains(.option),
                        command: m.contains(.command))
}
