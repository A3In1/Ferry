import AppKit
import FerryCore
import Observation

/// UI state; everything here runs on the main actor.
@MainActor @Observable
final class AppModel {
    struct Recent: Identifiable {
        let id: String // transfer id + item index
        var text: String
    }

    private(set) var node: FerryNode?
    private(set) var status = "Starting…"
    private(set) var peers: [FfiPeer] = []
    private(set) var recent: [Recent] = []
    private var offeredNames: [String: [String]] = [:]

    init() {
        Task { await start() }
    }

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
            refreshPeers()
        } catch {
            status = "Failed to start: \(error)"
        }
    }

    func refreshPeers() {
        peers = node?.peers() ?? []
    }

    func forget(_ peer: FfiPeer) {
        do { try node?.forgetPeer(peerId: peer.id) } catch { showAlert("Couldn't forget \(peer.name)", "\(error)") }
        refreshPeers()
    }

    func sendFiles(to peer: FfiPeer) {
        guard let node else { return }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.message = "Send to \(peer.name)"
        NSApp.activate()
        guard panel.runModal() == .OK else { return }
        let paths = panel.urls.map(\.path)
        Task {
            do {
                let r = try await node.sendFiles(peerId: peer.id, paths: paths)
                let mb = Double(r.bytesSent) / 1_000_000
                showAlert("Sent \(r.filesSent) file(s) to \(peer.name)",
                          String(format: "%.1f MB in %.1f s", mb, Double(r.elapsedMs) / 1000))
            } catch {
                showAlert("Send to \(peer.name) failed", "\(error)")
            }
        }
    }

    func newTicket() -> Result<String, Error> {
        guard let node else { return .failure(CancellationError()) }
        return Result { try node.newPairingTicket() }
    }

    // MARK: called via Bridge

    func confirmPairing(peerName: String, code: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = "Pair with \(peerName)?"
        alert.informativeText = "Check that \(peerName) shows the same code:\n\n\(code)"
        alert.addButton(withTitle: "Match")
        alert.addButton(withTitle: "Doesn't match")
        NSApp.activate()
        let ok = alert.runModal() == .alertFirstButtonReturn
        if ok {
            // shortcut: the core has no "paired" event, so re-read the list once pairing likely finished.
            Task {
                try? await Task.sleep(for: .seconds(2))
                refreshPeers()
            }
        }
        return ok
    }

    func handle(_ event: FfiFileEvent) {
        switch event {
        case let .offered(tid, names, totalBytes):
            offeredNames[tid] = names
            put(tid, "Incoming \(names.count) file(s), \(ByteCountFormatter.string(fromByteCount: Int64(totalBytes), countStyle: .file))")
        case let .progress(tid, index, done, total):
            let pct = total > 0 ? Int(done * 100 / total) : 100
            put("\(tid)/\(index)", "\(name(tid, index)) — \(pct)%")
        case let .saved(tid, index, path):
            put("\(tid)/\(index)", "Saved \((path as NSString).lastPathComponent)")
        case let .failed(tid, index, error):
            put("\(tid)/\(index)", "\(name(tid, index)) failed: \(error)")
        }
    }

    private func name(_ tid: String, _ index: UInt32) -> String {
        let names = offeredNames[tid] ?? []
        return Int(index) < names.count ? names[Int(index)] : "File \(index + 1)"
    }

    private func put(_ id: String, _ text: String) {
        if let i = recent.firstIndex(where: { $0.id == id }) {
            recent[i].text = text
        } else {
            recent.insert(Recent(id: id, text: text), at: 0)
            recent = Array(recent.prefix(5))
        }
    }

    private func showAlert(_ title: String, _ text: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        NSApp.activate()
        alert.runModal()
    }
}

/// Receives callbacks from Rust worker threads and hops to the main actor.
final class Bridge: FerryDelegate {
    let model: AppModel // @MainActor classes are Sendable

    init(model: AppModel) { self.model = model }

    func onFileEvent(event: FfiFileEvent) {
        Task { @MainActor in model.handle(event) }
    }

    func confirmPairing(peerName: String, code: String) async -> Bool {
        await model.confirmPairing(peerName: peerName, code: code)
    }
}
