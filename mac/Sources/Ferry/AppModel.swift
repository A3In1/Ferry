import AppKit
import FerryCore
import UserNotifications

/// Watch adb for phones plugged in over USB (usbWatch at startup, usbLinks poll, "Pair phone over USB").
let usbEnabled = true

/// App state; everything here runs on the main actor.
@MainActor
final class AppModel {
    struct Line {
        let id: String // transfer id, or "send-<uuid>"
        var text: String
    }

    private(set) var node: FerryNode?
    private(set) var status = "Starting…"
    private(set) var peers: [FfiPeer] = []
    private(set) var usbLinks: [FfiUsbLink] = []
    /// Newest first: active transfers update in place, finished ones stay until pushed out.
    private(set) var lines: [Line] = []
    /// Called after anything shown in the menu changed.
    var onChange: () -> Void = {}

    private struct Incoming {
        var names: [String]
        var totalBytes: UInt64
        var finishedBytes: UInt64 = 0
        var saved: [URL] = []
        var failed = 0
        var rateStart: (Date, UInt64)? // first progress seen, for MB/s (resumes start mid-way)
    }
    private var incoming: [String: Incoming] = [:]
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

    init() {
        startTask = Task { await start() }
        Task {
            while true {
                try? await Task.sleep(for: .seconds(2))
                tick()
            }
        }
        Task {
            while true {
                try? await Task.sleep(for: .seconds(1))
                pollClipboard()
            }
        }
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
            status = "\(node.deviceName()) · \(node.deviceId().prefix(8))"
            if usbEnabled {
                do { try await node.usbWatch(adbPath: nil) } catch { put("usb", "USB unavailable: \(error)") }
            }
            bluetooth = Bluetooth(node: node) { [weak self] note in
                Task { @MainActor in
                    self?.bluetoothNote = note
                    self?.onChange()
                }
            }
        } catch {
            status = "Failed to start: \(error)"
        }
        tick()
    }

    /// Cheap (in-memory lists); also catches pairings that finished since the last look.
    func tick() {
        refreshPeers()
        // USB and Bluetooth bridges (serial "ble:…") both show up here.
        if let node { usbLinks = node.usbLinks() }
        bluetooth?.setWanted(bluetoothEnabled && !peers.isEmpty)
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

    func peerName(_ id: String) -> String? {
        peers.first { $0.id == id }?.name
    }

    /// Drop / Services target: the only peer, else the last one we sent to.
    var preferredPeer: FfiPeer? {
        if peers.count == 1 { return peers[0] }
        let last = UserDefaults.standard.string(forKey: "lastPeerId")
        return peers.first { $0.id == last }
    }

    func forget(_ peer: FfiPeer) {
        do { try node?.forgetPeer(peerId: peer.id) } catch { showAlert("Couldn't forget \(peer.name)", "\(error)") }
        refreshPeers()
    }

    func chooseAndSend(to peer: FfiPeer) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.message = "Send to \(peer.name)"
        NSApp.activate()
        guard panel.runModal() == .OK else { return }
        send(panel.urls, to: peer)
    }

    func send(_ urls: [URL], to peer: FfiPeer) {
        guard let node, !urls.isEmpty else { return }
        UserDefaults.standard.set(peer.id, forKey: "lastPeerId")
        let id = "send-\(UUID().uuidString)"
        let what = urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files"
        put(id, "↑ Sending \(what) to \(peer.name)…")
        Task {
            do {
                let r = try await node.sendFiles(peerId: peer.id, paths: urls.map(\.path))
                let secs = max(Double(r.elapsedMs) / 1000, 0.001)
                put(id, "Sent \(what) to \(peer.name) · \(size(r.bytesSent)), "
                    + String(format: "%.1f MB/s", Double(r.bytesSent) / 1e6 / secs))
            } catch {
                put(id, "Sending \(what) to \(peer.name) failed")
                showAlert("Send to \(peer.name) failed", "\(error)")
            }
        }
    }

    func newTicket() -> Result<String, Error> {
        guard let node else { return .failure(CancellationError()) }
        return Result { try node.newPairingTicket() }
    }

    func pairOverUSB() {
        guard usbEnabled, let node else { return }
        Task {
            do {
                try await node.usbPushTicket(serial: nil)
                put("usb-pair", "Check your phone for the pairing code")
            } catch {
                showAlert("Couldn't pair over USB", "\(error)")
            }
        }
    }

    // MARK: called via Bridge

    func confirmPairing(peerName: String, code: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = "Pair with \(peerName)?"
        alert.informativeText = "Check that \(peerName) shows the same code:\n\n\(code)"
        alert.addButton(withTitle: "Match")
        alert.addButton(withTitle: "Doesn't match")
        NSApp.activate()
        return alert.runModal() == .alertFirstButtonReturn
    }

    func handle(_ event: FfiFileEvent) {
        switch event {
        case let .offered(tid, names, totalBytes):
            incoming[tid] = Incoming(names: names, totalBytes: totalBytes)
            put(tid, "↓ Incoming \(describe(names)) (\(size(totalBytes)))")
        case let .progress(tid, index, done, _):
            guard var t = incoming[tid] else { return }
            let bytes = t.finishedBytes + done
            if t.rateStart == nil { t.rateStart = (.now, bytes) }
            incoming[tid] = t
            let pct = t.totalBytes > 0 ? min(100, bytes * 100 / t.totalBytes) : 100
            var text = "↓ \(t.names.count > 1 ? "\(index + 1)/\(t.names.count) " : "")\(name(t, index)) — \(pct)%"
            if case let (start, startBytes)? = t.rateStart {
                let secs = Date.now.timeIntervalSince(start)
                if secs > 0.5 { text += String(format: " · %.1f MB/s", (Double(bytes) - Double(startBytes)) / 1e6 / secs) }
            }
            put(tid, text)
        case let .saved(tid, _, path):
            let url = URL(filePath: path)
            quarantine(url)
            var t = incoming[tid] ?? Incoming(names: [url.lastPathComponent], totalBytes: 0)
            t.saved.append(url)
            t.finishedBytes += UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            incoming[tid] = t
            finishIfDone(tid)
        case let .failed(tid, index, error):
            var t = incoming[tid] ?? Incoming(names: [], totalBytes: 0)
            t.failed += 1
            incoming[tid] = t
            put(tid, "\(name(t, index)) failed: \(error)")
            finishIfDone(tid)
        }
    }

    private func finishIfDone(_ tid: String) {
        guard let t = incoming[tid], t.saved.count + t.failed >= t.names.count else { return }
        incoming[tid] = nil
        if !t.saved.isEmpty {
            let what = describe(t.saved.map(\.lastPathComponent))
            put(tid, t.failed == 0 ? "Received \(what)" : "Received \(what), \(t.failed) failed")
            notifyReceived(t.saved, what: what)
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
        do { try url.setResourceValues(values) } catch { NSLog("Ferry: couldn't quarantine \(url.path): \(error)") }
    }

    private func notifyReceived(_ urls: [URL], what: String) {
        guard canNotify else { return }
        let content = UNMutableNotificationContent()
        content.title = "Received \(what)"
        content.body = "Saved to \(urls[0].deletingLastPathComponent().lastPathComponent)"
        content.categoryIdentifier = Notifications.received
        content.userInfo = [Notifications.pathsKey: urls.map(\.path)]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    private func describe(_ names: [String]) -> String {
        names.count == 1 ? names[0] : "\(names.count) files"
    }

    private func name(_ t: Incoming, _ index: UInt32) -> String {
        Int(index) < t.names.count ? t.names[Int(index)] : "File \(index + 1)"
    }

    private func size(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }

    private func put(_ id: String, _ text: String) {
        if let i = lines.firstIndex(where: { $0.id == id }) {
            lines[i].text = text
        } else {
            lines.insert(Line(id: id, text: text), at: 0)
            lines = Array(lines.prefix(5))
        }
        onChange()
    }

    func showAlert(_ title: String, _ text: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        NSApp.activate()
        alert.runModal()
    }
}

enum Notifications {
    static let received = "received"
    static let showAction = "show"
    static let pathsKey = "paths"
    /// Phone notifications use request ids "n|<peerId>|<key>" and carry these in userInfo.
    static let phonePrefix = "n|"
    static let peerKey = "peer"
    static let keyKey = "key"
    /// Action ids for phone buttons are "b|<buttonId>" (keeps them apart from the system's ids).
    static let buttonPrefix = "b|"
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

    func confirmPairing(peerName: String, code: String) async -> Bool {
        let ok = await model.confirmPairing(peerName: peerName, code: code)
        // The trust store is written right after we return; the 2 s tick shows the peer if this is too early.
        if ok { await model.refreshPeers() }
        return ok
    }
}
