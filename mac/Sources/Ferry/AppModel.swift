import AppKit
import FerryCore
import FerryLogic
import os
import UserNotifications

let logger = Logger(subsystem: "dev.ferry.mac", category: "app")

/// App state; everything here runs on the main actor.
@MainActor
final class AppModel {
    struct Line {
        let id: String // transfer id, or "send-<uuid>"
        var text: String
        var symbol: String // SF Symbol shown beside it in the menu
    }

    private(set) var node: FerryNode?
    /// Shown in the menu (and alerts) until the node is up; "" once it is.
    private(set) var status = "Starting Ferry…"
    private(set) var startFailed = false
    /// adb isn't available, so USB pairing and USB links can't work.
    private(set) var usbUnavailable = false
    private(set) var peers: [FfiPeer] = []
    private(set) var usbLinks: [FfiUsbLink] = []
    /// Newest first: active transfers update in place, finished ones stay until pushed out.
    private(set) var lines: [Line] = []
    /// Called after anything shown in the menu changed.
    var onChange: () -> Void = {}

    /// Transfers with a progress bar (Transfers.swift): running ones, and ones that just ended.
    let transferList = TransferList()
    var transfersPanel: TransfersPanel?
    private var startTask: Task<Void, Never>?
    private var bluetooth: Bluetooth?
    /// "Bluetooth off" etc. while Bluetooth should run but can't.
    private(set) var bluetoothNote: String?
    // UNUserNotificationCenter traps outside an app bundle (`swift run`).
    let canNotify = Bundle.main.bundleIdentifier != nil

    // Phase 2 state (Sync.swift), per peer id.
    struct NowPlaying { var title, artist, app: String; var playing: Bool }
    var nowPlaying: [String: NowPlaying] = [:]
    var battery: [String: (percent: UInt32, charging: Bool)] = [:]
    /// Phone-notification categories registered so far, by id (see `categoryID`).
    var categories: [String: UNNotificationCategory] = [:]
    /// Last general-pasteboard changeCount we've seen or caused (echo suppression).
    var seenClipboardChange = NSPasteboard.general.changeCount
    /// The 1 s clipboard poll; only runs while "Sync clipboard to phone" is on.
    var clipboardPoll: Task<Void, Never>?
    /// Phones with a synced copy in flight, and the newest copy waiting behind each (Sync.swift).
    var clipboardSending: Set<String> = []
    var clipboardNext: [String: String] = [:]
    // Phase 4 (Screen.swift, Crossing.swift): open viewer windows by peer id, and cursor sharing.
    var viewers: [String: ScreenWindow] = [:]
    var phoneWindows: [String: Process] = [:] // Open Phone, per phone
    var crossing: Crossing?
    // Phase 5 (Messages.swift), by peer id: texts and calls, open Messages windows, who an active call is with.
    var phoneTexts: [String: PhoneTexts] = [:]
    var messageWindows: [String: MessagesWindow] = [:]
    var activeCalls: [String: String] = [:]
    // Phase 6: phone file browsing (Files.swift) by peer id, and the phone's last reported Do Not Disturb (WifiFocus.swift).
    var phoneFiles: [String: PhoneFiles] = [:]
    var fileWindows: [String: FilesWindow] = [:]
    var phoneDnd: [String: Bool] = [:]
    /// When each phone last sent us anything (a hint that it's reachable over the network).
    var lastHeard: [String: Date] = [:]
    /// After the user confirms a pairing code (closes the Pair window).
    var onPaired: (String) -> Void = { _ in }

    init() {
        startTask = Task { await start() }
        // Tolerance lets macOS coalesce the wake-ups with other timers.
        Task { while (try? await Task.sleep(for: .seconds(2), tolerance: .seconds(1))) != nil { tick() } }
        updateClipboardPoll()
    }

    /// Returns once the node has started (or failed to).
    func ready() async { await startTask?.value }

    private func start() async {
        let fm = FileManager.default
        let dataDir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appending(path: "Ferry")
        let downloads = fm.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        do {
            try fm.createDirectory(at: dataDir, withIntermediateDirectories: true)
            let node = try await FerryNode.start(
                dataDir: dataDir.path,
                deviceName: Host.current().localizedName ?? "Mac",
                downloadDir: downloads.path,
                delegate: Bridge(model: self)
            )
            self.node = node
            status = ""
            do { try await node.usbWatch(adbPath: nil) } catch {
                logger.notice("USB unavailable: \(error, privacy: .public)")
                usbUnavailable = true
            }
            bluetooth = Bluetooth(node: node) { [weak self] note in
                Task { @MainActor in
                    self?.bluetoothNote = note
                    self?.onChange()
                }
            }
        } catch {
            logger.error("node failed to start: \(error, privacy: .public)")
            status = "Ferry couldn't start"
            startFailed = true
        }
        tick()
    }

    /// Cheap (in-memory lists); also catches pairings that finished since the last look.
    func tick() {
        refreshPeers()
        // USB and Bluetooth bridges (serial "ble:…") both show up here.
        if let node { usbLinks = node.usbLinks() }
        bluetooth?.setWanted(bluetoothEnabled && !peers.isEmpty)
        dropStalled()
        updateCrossing()
        onChange()
    }

    /// Menu toggle "Bluetooth" (default on). It only runs while a phone is paired.
    var bluetoothEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "bluetooth") as? Bool ?? true }
        set {
            UserDefaults.standard.set(newValue, forKey: "bluetooth")
            tick()
        }
    }

    func refreshPeers() {
        peers = node?.peers() ?? []
    }

    func peer(_ id: String) -> FfiPeer? { peers.first { $0.id == id } }

    func peerName(_ id: String) -> String? { peer(id)?.name }

    /// How the phone is reached, for the menu: (line, SF Symbol). USB and Bluetooth links are known; Wi-Fi is
    /// inferred from the phone having sent something lately (the core doesn't expose live connection state).
    func connection(_ id: String) -> (String, String) {
        if let link = usbLinks.first(where: { $0.peerId == id }) {
            return link.serial.hasPrefix("ble:") ? ("Connected over Bluetooth", "dot.radiowaves.left.and.right")
                : ("Connected over USB", "cable.connector")
        }
        guard let heard = lastHeard[id] else { return ("Paired", "iphone") }
        if Date.now.timeIntervalSince(heard) < 600 { return ("Connected over Wi-Fi", "wifi") }
        return ("Last seen \(heard.formatted(.relative(presentation: .named)))", "iphone")
    }

    /// Drop / Services target: the only peer, else the last one we sent to.
    var preferredPeer: FfiPeer? {
        if peers.count == 1 { return peers[0] }
        let last = UserDefaults.standard.string(forKey: "lastPeerId")
        return peers.first { $0.id == last }
    }

    func forget(_ peer: FfiPeer) {
        guard confirm("Forget \(peer.name)?", "This Mac and \(peer.name) stop connecting to each other. To use them together "
                          + "again, pair them again.", "Forget") else { return }
        do {
            try node?.forgetPeer(peerId: peer.id)
            // Else its battery, now-playing and call lines stay in the menu.
            battery[peer.id] = nil
            nowPlaying[peer.id] = nil
            activeCalls[peer.id] = nil
            lastHeard[peer.id] = nil
            phoneDnd[peer.id] = nil
            phoneTexts[peer.id] = nil // its cached threads and messages
            phoneFiles[peer.id] = nil
            for w in [viewers[peer.id], messageWindows[peer.id], fileWindows[peer.id]] as [PeerWindow?] { w?.window.close() }
            removeNotifications { $0.request.content.userInfo[Notifications.peerKey] as? String == peer.id }
        } catch {
            logger.error("forget failed: \(error, privacy: .public)")
            showAlert("Couldn't forget \(peer.name)", "Try again. If it keeps happening, quit Ferry and open it again.")
        }
        refreshPeers()
    }

    func chooseAndSend(to peer: FfiPeer) {
        chooseFiles("Choose files to send to \(peer.name)", button: "Send").map { send($0, to: peer) }
    }

    func chooseFiles(_ message: String, button: String) -> [URL]? {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.message = message
        panel.prompt = button
        NSApp.activate()
        return panel.runModal() == .OK ? panel.urls : nil
    }

    func send(_ urls: [URL], to peer: FfiPeer) {
        guard let node, !urls.isEmpty else { return }
        UserDefaults.standard.set(peer.id, forKey: "lastPeerId")
        // Our own id, so the row's Cancel works before the core's first event for it.
        let t = LiveTransfer(id: newTransferID(), outgoing: true, peerName: peer.name, names: urls.map(\.lastPathComponent))
        let what = describe(t.progress.names)
        begin(t)
        Task {
            do {
                let r = try await node.sendFiles(peerId: peer.id, paths: urls.map(\.path), transferId: t.id)
                let line = "Sent \(what) to \(peer.name) · \(byteCount(r.bytesSent)), "
                    + rate(Double(r.bytesSent), seconds: max(Double(r.elapsedMs) / 1000, 0.001))
                end(t, .done("Sent"), line: line, symbol: "checkmark.circle")
            } catch let FerryError.Cancelled(byPeer) {
                end(t, .cancelled(byPeer ? "Cancelled on \(peer.name)" : "Cancelled"), line: cancelledLine(t, byPeer: byPeer),
                    symbol: "xmark.circle")
            } catch {
                logger.error("send to \(peer.name) failed: \(error, privacy: .public)")
                end(t, .failed("Couldn't send"), line: "Couldn't send \(what) to \(peer.name)", symbol: "exclamationmark.triangle")
                showAlert("Couldn't send \(what) to \(peer.name)", reachHint(peer.name))
            }
        }
    }

    func newTicket() -> Result<String, Error> {
        guard let node else { return .failure(CancellationError()) }
        return Result { try node.newPairingTicket() }
    }

    func pairOverUSB() {
        guard let node else { return }
        Task {
            do {
                try await node.usbPushTicket(serial: nil)
                put("usb-pair", "Pairing over USB: check your phone", "cable.connector")
            } catch {
                logger.error("USB pairing failed: \(error, privacy: .public)")
                showAlert("Couldn't pair over USB", usbHelp("\(error)"))
            }
        }
    }

    // MARK: called via Bridge

    func confirmPairing(peerName: String, code: String) -> Bool {
        let ok = showAlert("Pair with \(peerName)?", "Check that \(peerName) shows the same code:\n\n\(code)",
                           buttons: ["Match", "Doesn't Match"])
        if ok { onPaired(peerName) }
        return ok
    }

    func handle(_ event: FfiFileEvent) {
        switch event {
        case let .sending(tid, names, totalBytes):
            // Every send starts in send() (drops, Services, Browse Files uploads), which made the row already.
            guard let t = transfer(tid) else { return }
            t.raw.names = names
            t.raw.totalBytes = totalBytes
            t.publish(now: true)
        case let .offered(tid, names, totalBytes):
            begin(LiveTransfer(id: tid, outgoing: false, peerName: nil, names: names, totalBytes: totalBytes))
        case let .progress(tid, index, done, total):
            guard let t = transfer(tid), t.running else { return }
            t.raw.progress(index: index, done: done, total: total)
            t.publish()
        case let .saved(tid, index, path):
            guard let t = transfer(tid), t.running else { return }
            t.raw.finish(index: index)
            t.publish()
            // Sender side: `path` is the name the phone saved it under; the send's own result reports it.
            guard !t.outgoing else { return }
            let url = URL(filePath: path)
            quarantine(url)
            t.saved.append(url)
            finishIfDone(t)
        case let .failed(tid, index, error):
            guard let t = transfer(tid), t.running else { return }
            t.raw.finish(index: index)
            t.failed += 1
            t.publish()
            guard !t.outgoing else { return }
            logger.error("receiving \(self.name(t, index), privacy: .private) failed: \(error, privacy: .public)")
            finishIfDone(t)
        case let .cancelled(tid, byPeer):
            // A send's own result (FerryError.Cancelled) ends its row.
            guard let t = transfer(tid), t.running, !t.outgoing else { return }
            end(t, .cancelled(byPeer ? "Cancelled on the phone" : "Cancelled"), line: cancelledLine(t, byPeer: byPeer), symbol: "xmark.circle")
            if !t.saved.isEmpty {
                notifyReceived(t.saved, what: describe(t.saved.map(\.lastPathComponent)), failed: 0, id: tid)
            }
        }
    }

    private func name(_ t: LiveTransfer, _ index: UInt32) -> String {
        Int(index) < t.raw.names.count ? t.raw.names[Int(index)] : "File \(index + 1)"
    }

    /// `stalled`: no event for a while (the connection dropped), so the rest won't come.
    private func finishIfDone(_ t: LiveTransfer, stalled: Bool = false) {
        guard t.running, stalled || t.saved.count + t.failed >= t.raw.names.count else { return }
        let failed = max(t.failed, t.raw.names.count - t.saved.count)
        if !t.saved.isEmpty {
            let what = describe(t.saved.map(\.lastPathComponent))
            end(t, failed == 0 ? .done("Received") : .failed("\(failed) couldn't be received"),
                line: failed == 0 ? "Received \(what)" : "Received \(what); \(failed) couldn't be received", symbol: "checkmark.circle")
            notifyReceived(t.saved, what: what, failed: failed, id: t.id)
        } else {
            let what = describe(t.raw.names)
            end(t, .failed(stalled ? "The connection was lost" : "Couldn't receive"),
                line: stalled ? "Couldn't receive \(what): the connection was lost" : "Couldn't receive \(what)",
                symbol: "exclamationmark.triangle")
        }
    }

    /// The core sends nothing more for a transfer whose connection dropped; without this its row would
    /// stay at 45% for good.
    private func dropStalled() {
        for t in transferList.items where t.running && !t.outgoing && Date.now.timeIntervalSince(t.lastEvent) > 120 {
            logger.notice("transfer \(t.id.prefix(8), privacy: .public) stalled, giving up")
            finishIfDone(t, stalled: true)
        }
    }

    /// Mark like a browser download so Gatekeeper checks it on first open.
    private func quarantine(_ url: URL) {
        var url = url
        var values = URLResourceValues()
        values.quarantineProperties = [
            kLSQuarantineAgentNameKey as String: "Ferry",
            kLSQuarantineTypeKey as String: kLSQuarantineTypeOtherDownload as String,
        ]
        do { try url.setResourceValues(values) } catch { logger.error("couldn't quarantine \(url.path): \(error, privacy: .public)") }
    }

    func notifyReceived(_ urls: [URL], what: String, failed: Int, id: String) {
        let folder = urls[0].deletingLastPathComponent().lastPathComponent
        notify("Received \(what)", "In your \(folder) folder" + (failed > 0 ? ". \(failed) couldn't be received." : ""), id: "f|\(id)") {
            $0.threadIdentifier = Notifications.received
            $0.categoryIdentifier = Notifications.received
            $0.userInfo = [Notifications.pathsKey: urls.map(\.path)]
        }
    }

    func put(_ id: String, _ text: String, _ symbol: String) {
        if let i = lines.firstIndex(where: { $0.id == id }) {
            lines[i].text = text
            lines[i].symbol = symbol
        } else {
            lines.insert(Line(id: id, text: text, symbol: symbol), at: 0)
            lines = Array(lines.prefix(5))
        }
        onChange()
    }

    /// Modal alert; true if the first button was chosen. No `buttons` = a single OK.
    @discardableResult func showAlert(_ title: String, _ text: String, buttons: [String] = []) -> Bool {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        buttons.forEach { alert.addButton(withTitle: $0) }
        if alert.buttons.count == 2 { alert.buttons[1].keyEquivalent = "\u{1b}" } // Escape = the second choice ("Doesn't Match", "Cancel")
        NSApp.activate()
        return alert.runModal() == .alertFirstButtonReturn
    }

    func confirm(_ title: String, _ text: String, _ ok: String) -> Bool {
        showAlert(title, text, buttons: [ok, "Cancel"])
    }

    /// Alert body for a phone that didn't answer.
    func reachHint(_ name: String) -> String {
        "Make sure \(name) is nearby and Ferry is running on it, then try again."
    }

    /// Alert for an action that needs the node: still starting, or failed.
    func showNotRunning() {
        if startFailed { showAlert("Ferry couldn't start", "Quit Ferry and open it again.") } else {
            showAlert("Ferry is still starting", "Try again in a moment.")
        }
    }

    /// The core's adb errors, as something to do.
    private func usbHelp(_ error: String) -> String {
        if error.contains("adb not found") {
            return "Pairing over USB needs Android platform-tools (adb). Install them, for example with Homebrew "
                + "(brew install android-platform-tools), then open Ferry again. Or pair with the QR code instead."
        }
        if error.contains("no phone ready") { return "Plug in your phone with a USB cable and allow USB debugging when the phone asks." }
        if error.contains("several phones") { return "More than one phone is plugged in. Unplug all but the one you want to pair." }
        if error.contains("is it installed") { return "Install Ferry on your phone and open it once, then try again." }
        return "Make sure your phone is plugged in and unlocked, then try again. Or pair with the QR code instead."
    }

    /// Removes delivered notifications that `match` (stale texts, a forgotten phone's).
    func removeNotifications(_ match: @escaping (UNNotification) -> Bool) {
        guard canNotify else { return }
        let center = UNUserNotificationCenter.current()
        Task {
            let ids = await center.deliveredNotifications().filter(match).map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }

    /// Posts a notification now (a later one with the same `id` replaces it); a no-op outside an app bundle.
    /// `waitForCategories`: for a category set just now, else the first one shows without its buttons.
    func notify(_ title: String, _ body: String, id: String = UUID().uuidString, waitForCategories: Bool = false,
                _ configure: (UNMutableNotificationContent) -> Void = { _ in }) {
        guard canNotify else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        configure(content)
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        let center = UNUserNotificationCenter.current()
        guard waitForCategories else { return center.add(request) }
        Task {
            _ = await center.notificationCategories()
            do { try await center.add(request) } catch { logger.error("couldn't post notification: \(error, privacy: .public)") }
        }
    }

    /// Runs `silent` in 5 s unless `heard()` has changed by then (the phone answered).
    func ifUnanswered(_ heard: @escaping () -> Int, _ silent: @escaping () -> Void) {
        let n = heard()
        Task {
            try? await Task.sleep(for: .seconds(5))
            if heard() == n { silent() }
        }
    }
}

func describe(_ names: [String]) -> String { names.count == 1 ? names[0] : "\(names.count) files" }

func byteCount(_ bytes: UInt64) -> String { ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file) }

func rate(_ bytes: Double, seconds: Double) -> String { String(format: "%.1f MB/s", bytes / 1e6 / seconds) }

extension Dictionary {
    /// The value for `key`, after inserting `make()` if there was none.
    mutating func value(_ key: Key, orInsert make: () -> Value) -> Value {
        if let v = self[key] { return v }
        let v = make()
        self[key] = v
        return v
    }
}

enum Notifications {
    static let received = "received" // category and thread
    static let showAction = "show"
    static let pathsKey = "paths"
    /// Phone notifications use request ids "n|<peerId>|<key>" and carry these in userInfo.
    static let phonePrefix = "n|"
    static let peerKey = "peer"
    static let keyKey = "key"
    /// Action ids for phone buttons are "b|<buttonId>" (keeps them apart from the system's ids).
    static let buttonPrefix = "b|"
    /// Phase 5: texts are "s|<peerId>|<messageId>", the ringing call "c|<peerId>".
    static let smsPrefix = "s|", callPrefix = "c|"
    static let sms = "sms", smsCode = "sms-code", call = "call"
    static let replyAction = "reply", copyAction = "copy", answerAction = "answer", declineAction = "decline"
    static let addressKey = "address", threadKey = "thread", codeKey = "code"
    /// "A new Ferry is available" from a background update check (Updates.swift).
    static let update = "update"
}

/// Receives callbacks from Rust worker threads and hops to the main actor.
final class Bridge: FerryDelegate {
    let model: AppModel // @MainActor classes are Sendable

    init(model: AppModel) { self.model = model }

    func onFileEvent(event: FfiFileEvent) {
        Task { @MainActor in model.handle(event) }
    }

    func onEvent(peerId: String, event: FfiEvent) {
        Task { @MainActor in model.handle(event, from: peerId) }
    }

    /// Phones open screen streams, Macs don't accept them.
    func onScreenStream(peerId: String, stream: ScreenStream) {
        Task { await stream.finish() }
    }

    func confirmPairing(peerName: String, code: String) async -> Bool {
        let ok = await model.confirmPairing(peerName: peerName, code: code)
        // The trust store is written right after we return; the 2 s tick shows the peer if this is too early.
        if ok { await model.refreshPeers() }
        return ok
    }
}
