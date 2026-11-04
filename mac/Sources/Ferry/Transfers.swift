import AppKit
import FerryCore
import FerryLogic
import SwiftUI

/// A transfer with a progress bar (menu rows and the Transfers panel), either direction.
/// Progress arrives up to ~30 times a second with 8 files in flight; the view sees it at most 10.
@MainActor @Observable final class LiveTransfer: Identifiable {
    enum State: Equatable { case running, done(String), failed(String), cancelled(String) }

    let id: String // the core's transfer id
    let outgoing: Bool
    let peerName: String?
    private(set) var progress: TransferProgress
    /// A Browse Files download over USB (`adb pull`) shows this instead of `progress`.
    private(set) var usb: UsbPullState?
    var state = State.running
    let started = Date.now

    @ObservationIgnored var raw: TransferProgress
    @ObservationIgnored var rawUsb: UsbPullState?
    /// Set for transfers the core doesn't run (USB downloads); else Cancel goes to the core.
    @ObservationIgnored var onCancel: (() -> Void)?
    @ObservationIgnored var lastEvent = Date.now // a dropped connection sends no more events (see dropStalled)
    @ObservationIgnored var saved: [URL] = []
    @ObservationIgnored var failed = 0
    @ObservationIgnored private var lastPublish = Date.distantPast
    @ObservationIgnored private var publishPending = false

    init(id: String, outgoing: Bool, peerName: String?, names: [String], totalBytes: UInt64 = 0, usb: UsbPullState? = nil) {
        self.id = id
        self.outgoing = outgoing
        self.peerName = peerName
        let p = TransferProgress(names: names, totalBytes: totalBytes)
        progress = p
        raw = p
        self.usb = usb
        rawUsb = usb
    }

    var running: Bool { state == .running }

    /// Hands `raw` to the view, throttled to 10 a second (with a trailing update so the last state shows).
    func publish(now: Bool = false) {
        lastEvent = .now
        let wait = 0.1 - Date.now.timeIntervalSince(lastPublish)
        if now || wait <= 0 {
            progress = raw
            usb = rawUsb
            lastPublish = .now
            return
        }
        guard !publishPending else { return }
        publishPending = true
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait))
            guard let self else { return }
            publishPending = false
            progress = raw
            usb = rawUsb
            lastPublish = .now
        }
    }

    var title: String {
        let what = describe(progress.names)
        if usb != nil { return "Downloading \(what)" + (peerName.map { " from \($0)" } ?? "") }
        if outgoing { return "Sending \(what)" + (peerName.map { " to \($0)" } ?? "") }
        return "Receiving \(what)" + (peerName.map { " from \($0)" } ?? "")
    }

    /// 0...1, or nil for an indeterminate bar.
    var fraction: Double? { usb.map(\.fraction) ?? progress.fraction }

    /// "File 2 of 3: name"
    var fileLine: String? {
        if let usb { return usb.fileLine }
        let p = progress
        guard p.fileCount > 1 else { return nil }
        return "File \(p.fileNumber) of \(p.fileCount)" + (p.currentName.map { ": \($0)" } ?? "")
    }

    func status(at now: Date) -> String {
        switch state {
        case .running:
            if let usb { return usb.detail(at: now) }
            return progress.totalBytes == 0 ? "Connecting…" : progress.detail(at: now)
        case let .done(s), let .failed(s), let .cancelled(s): return s
        }
    }
}

/// Icon, title, "File 2 of 3: name", bar, "12 MB of 40 MB · 25 MB/s · 2 s left", and Cancel.
struct TransferRow: View {
    let transfer: LiveTransfer
    let cancel: () -> Void

    var body: some View {
        let running = transfer.running
        // Fixed layout (hidden, not removed), so a menu row keeps its height when the transfer ends.
        HStack(alignment: .center, spacing: 10) {
            Image(systemName: symbol).font(.title2).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(transfer.title).lineLimit(1).truncationMode(.middle)
                if let line = transfer.fileLine {
                    Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle)
                }
                Group {
                    if let f = transfer.fraction { ProgressView(value: f) } else { ProgressView().progressViewStyle(.linear) }
                }
                .controlSize(.small).opacity(running ? 1 : 0)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(transfer.status(at: ctx.date)).font(.caption).foregroundStyle(.secondary).monospacedDigit().lineLimit(1)
                }
            }
            Button(action: cancel) { Image(systemName: "xmark.circle.fill").font(.title3) }
                .buttonStyle(.borderless).foregroundStyle(.secondary)
                .help("Cancel")
                .accessibilityLabel("Cancel \(transfer.title)")
                .opacity(running ? 1 : 0).disabled(!running)
        }
        .padding(.vertical, 4)
    }

    private var symbol: String {
        switch transfer.state {
        case .running: transfer.outgoing ? "arrow.up.circle" : "arrow.down.circle"
        case .done: "checkmark.circle"
        case .failed: "exclamationmark.triangle"
        case .cancelled: "xmark.circle"
        }
    }
}

/// A small floating panel listing transfers while any run; opens for transfers that take a while.
@MainActor final class TransfersPanel: NSObject, NSWindowDelegate {
    let panel: NSPanel
    private(set) var userClosed = false

    init(model: AppModel) {
        panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 80),
                        styleMask: [.titled, .closable, .utilityWindow, .nonactivatingPanel], backing: .buffered, defer: false)
        super.init()
        panel.title = "Transfers"
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.delegate = self
        let host = NSHostingController(rootView: TransfersList(model: model).frame(width: 360))
        host.sizingOptions = [.preferredContentSize] // the panel grows and shrinks with the list
        panel.contentViewController = host
        if !panel.setFrameUsingName("Transfers") {
            // top right, under the menu bar, where the menu-bar icon is
            if let screen = NSScreen.main?.visibleFrame {
                panel.setFrameTopLeftPoint(NSPoint(x: screen.maxX - 380, y: screen.maxY - 12))
            }
        }
        panel.setFrameAutosaveName("Transfers")
    }

    func show() {
        userClosed = false
        panel.orderFrontRegardless()
    }

    /// Nothing left to show; the next long transfer may open the panel again.
    func idle() {
        userClosed = false
        panel.orderOut(nil)
    }

    func windowWillClose(_ notification: Notification) { userClosed = true }
}

struct TransfersList: View {
    let model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(model.transferList.items) { t in
                TransferRow(transfer: t) { model.cancel(t) }
                if t.id != model.transferList.items.last?.id { Divider() }
            }
        }
        .padding(12)
    }
}

/// Observable so the panel's list follows transfers starting and ending.
@MainActor @Observable final class TransferList {
    var items: [LiveTransfer] = [] // newest first
}

extension AppModel {
    func transfer(_ id: String) -> LiveTransfer? { transferList.items.first { $0.id == id } }

    func begin(_ t: LiveTransfer) {
        transferList.items.insert(t, at: 0)
        onChange()
        // Only transfers that take a while get the panel; a quick one is done before it would show.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self, t.running, !(transfersPanel?.userClosed ?? false) else { return }
            if transfersPanel == nil { transfersPanel = TransfersPanel(model: self) }
            transfersPanel?.show()
        }
    }

    /// The transfer stopped: its row shows the result a few seconds, then its line in Recent Transfers stays.
    func end(_ t: LiveTransfer, _ state: LiveTransfer.State, line: String, symbol: String) {
        guard t.running else { return }
        t.publish(now: true)
        t.state = state
        put(t.id, line, symbol)
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard let self else { return }
            transferList.items.removeAll { $0 === t }
            if transferList.items.isEmpty { transfersPanel?.idle() }
            onChange()
        }
    }

    /// Cancel button (menu row or panel). Both devices then report the transfer cancelled.
    func cancel(_ t: LiveTransfer) {
        guard t.running else { return }
        if let cancel = t.onCancel { return cancel() }
        guard let node else { return }
        if !node.cancelTransfer(transferId: t.id) {
            logger.notice("transfer \(t.id.prefix(8), privacy: .public) already over, nothing to cancel")
        }
    }

    func cancelledLine(_ t: LiveTransfer, byPeer: Bool) -> String {
        let what = describe(t.progress.names)
        let device = t.peerName ?? "the phone"
        let base = byPeer ? "\(what): cancelled on \(device)" : (t.outgoing ? "Cancelled sending \(what)" : "Cancelled receiving \(what)")
        return t.saved.isEmpty ? base : "\(base) · \(t.saved.count) saved"
    }
}
