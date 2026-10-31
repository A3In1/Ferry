import AppKit
import CoreImage.CIFilterBuiltins
import FerryCore
import ServiceManagement
import SwiftUI
import UserNotifications

/// Menu-bar agent. AppKit `NSStatusItem` (not `MenuBarExtra`) so the icon can take file drops.
@main @MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, NSWindowDelegate {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory) // LSUIElement does this in the bundle; this covers `swift run`
        withExtendedLifetime(delegate) { app.run() }
    }

    private let model = AppModel()
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let menu = NSMenu()
    private var menuOpen = false
    private var liveIndex = 0 // where the USB + transfer lines start
    private var liveItems: [NSMenuItem] = []
    private var pairWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let button = statusItem.button!
        button.image = NSImage(systemSymbolName: "ferry", accessibilityDescription: "Ferry")
        button.toolTip = "Ferry: drop files here to send them to your phone"
        // The button's window forwards drag callbacks to its delegate (the NSDraggingDestination methods below).
        button.window?.registerForDraggedTypes([.fileURL])
        button.window?.delegate = self

        menu.delegate = self
        statusItem.menu = menu

        NSApp.servicesProvider = self
        NSUpdateDynamicServices()

        if Bundle.main.bundleIdentifier != nil {
            let center = UNUserNotificationCenter.current()
            center.delegate = self
            center.setNotificationCategories([UNNotificationCategory(
                identifier: Notifications.received,
                actions: [UNNotificationAction(identifier: Notifications.showAction, title: "Show in Finder")],
                intentIdentifiers: []
            )])
            center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }

        model.onChange = { [weak self] in self?.refreshLive() }
    }

    // MARK: menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        model.refreshPeers()
        menu.removeAllItems()
        menu.addItem(label(model.status))
        liveIndex = menu.numberOfItems
        liveItems = []
        insertLive()
        menu.addItem(.separator())

        menu.addItem(ClosureItem("Pair a device…") { [weak self] in self?.showPairWindow() }.enabled(model.node != nil))
        menu.addItem(ClosureItem("Pair phone over USB") { [weak self] in self?.model.pairOverUSB() }
            .enabled(usbEnabled && model.node != nil))

        let devices = NSMenu()
        if model.peers.isEmpty { devices.addItem(label("None yet")) }
        for peer in model.peers {
            let sub = NSMenu()
            sub.addItem(ClosureItem("Send files to \(peer.name)…") { [weak self] in self?.model.chooseAndSend(to: peer) })
            sub.addItem(ClosureItem("Forget") { [weak self] in self?.model.forget(peer) })
            let item = NSMenuItem(title: peer.name, action: nil, keyEquivalent: "")
            item.submenu = sub
            devices.addItem(item)
        }
        let devicesItem = NSMenuItem(title: "Paired devices", action: nil, keyEquivalent: "")
        devicesItem.submenu = devices
        menu.addItem(devicesItem)

        menu.addItem(.separator())
        let login = ClosureItem("Open at Login") { [weak self] in self?.toggleLaunchAtLogin() }
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        menu.addItem(login)
        menu.addItem(.separator())
        menu.addItem(label("Core \(coreVersion())"))
        menu.addItem(ClosureItem("Quit Ferry", key: "q") { NSApp.terminate(nil) })
    }

    func menuWillOpen(_ menu: NSMenu) { menuOpen = true }
    func menuDidClose(_ menu: NSMenu) { menuOpen = false }

    /// USB + transfer lines, refreshed in place while the menu is open (progress, MB/s).
    private func refreshLive() {
        guard menuOpen else { return } // menuNeedsUpdate rebuilds everything on the next open
        liveItems.forEach(menu.removeItem)
        insertLive()
    }

    private func insertLive() {
        var items = model.usbLinks.map { link in
            label(model.peerName(link.peerId).map { "📱 \($0) — USB" } ?? "📱 Phone on USB (not paired)")
        }
        items += model.lines.map { label($0.text) }
        if !items.isEmpty { items.insert(.separator(), at: 0) }
        for (i, item) in items.enumerated() { menu.insertItem(item, at: liveIndex + i) }
        liveItems = items
    }

    private func label(_ text: String) -> NSMenuItem {
        let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func toggleLaunchAtLogin() {
        let service = SMAppService.mainApp
        do {
            if service.status == .enabled { try service.unregister() } else { try service.register() }
        } catch {
            model.showAlert("Couldn't change Open at Login", "\(error)")
        }
        if service.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
    }

    private func showPairWindow() {
        pairWindow?.close()
        let window = NSWindow(contentViewController: NSHostingController(rootView: PairView(model: model)))
        window.title = "Pair a device"
        window.styleMask.remove(.resizable)
        window.isReleasedWhenClosed = false
        window.center()
        pairWindow = window
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    // MARK: sending (drop + Services)

    private func send(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        Task {
            await model.ready() // Services can launch us; wait for the node before routing
            model.refreshPeers()
            if let peer = model.preferredPeer {
                model.send(urls, to: peer)
            } else if model.node == nil {
                model.showAlert("Ferry isn't running", model.status)
            } else if model.peers.isEmpty {
                model.showAlert("No paired phone", "Choose Pair a device… in the Ferry menu first.")
            } else {
                let chooser = NSMenu()
                for peer in model.peers {
                    chooser.addItem(ClosureItem("Send to \(peer.name)") { self.model.send(urls, to: peer) })
                }
                let button = statusItem.button!
                chooser.popUp(positioning: nil, at: NSPoint(x: 0, y: button.isFlipped ? button.bounds.maxY + 6 : -6), in: button)
            }
        }
    }

    private func fileURLs(_ pasteboard: NSPasteboard) -> [URL] {
        pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
    }

    /// Finder → Services → "Send with Ferry" (NSServices in Info.plist, NSMessage = sendFiles).
    @objc func sendFiles(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        send(fileURLs(pasteboard))
    }
}

// MARK: drop onto the menu-bar icon

extension AppDelegate: NSDraggingDestination {
    func draggingEntered(_ sender: any NSDraggingInfo) -> NSDragOperation {
        guard !fileURLs(sender.draggingPasteboard).isEmpty else { return [] }
        statusItem.button?.highlight(true)
        return .copy
    }

    func draggingExited(_ sender: (any NSDraggingInfo)?) {
        statusItem.button?.highlight(false)
    }

    func performDragOperation(_ sender: any NSDraggingInfo) -> Bool {
        statusItem.button?.highlight(false)
        let urls = fileURLs(sender.draggingPasteboard)
        send(urls)
        return !urls.isEmpty
    }
}

// MARK: notifications

extension AppDelegate: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    /// Body click and "Show in Finder" both reveal the files.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier != UNNotificationDismissActionIdentifier,
              let paths = response.notification.request.content.userInfo[Notifications.pathsKey] as? [String]
        else { return }
        await MainActor.run {
            NSWorkspace.shared.activateFileViewerSelecting(paths.map { URL(filePath: $0) })
        }
    }
}

/// Menu item that runs a closure (avoids a selector per item).
@MainActor final class ClosureItem: NSMenuItem {
    private let handler: @MainActor () -> Void

    init(_ title: String, key: String = "", handler: @escaping @MainActor () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: key)
        target = self
    }

    required init(coder: NSCoder) { fatalError("not used") }

    @objc private func fire() { handler() }

    func enabled(_ on: Bool) -> Self {
        isEnabled = on
        return self
    }
}

struct PairView: View {
    let model: AppModel
    @State private var ticket: Result<String, Error>?

    var body: some View {
        VStack(spacing: 12) {
            switch ticket {
            case .success(let t):
                if let qr = qrImage(t) {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 280, height: 280)
                        .accessibilityLabel("Pairing QR code")
                }
                Text("Scan with Ferry on your phone. Valid for 10 minutes.")
                    .font(.callout)
                Text(t)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .frame(width: 280)
                Button("Copy ticket") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(t, forType: .string)
                }
            case .failure(let e):
                Text(verbatim: "Couldn't create a ticket: \(e)")
            case nil:
                ProgressView()
            }
            Button("New code") { ticket = model.newTicket() }
        }
        .padding(20)
        .onAppear { ticket = model.newTicket() }
        .onDisappear { model.refreshPeers() }
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage,
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        // 1 px per module; `.interpolation(.none)` scales it up without blurring.
        return NSImage(cgImage: cg, size: out.extent.size)
    }
}
