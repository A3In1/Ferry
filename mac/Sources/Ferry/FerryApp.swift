import AppKit
import CoreImage.CIFilterBuiltins
import FerryCore
import FerryLogic
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
    private lazy var updates = Updates(model: model)
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let menu = NSMenu()
    private var menuOpen = false
    private var liveItems: [NSMenuItem] = [] // the status block under the updates item, refreshed while the menu is open
    private var updateItem: NSMenuItem?
    private var pairWindow: NSWindow?

    func application(_ application: NSApplication, open urls: [URL]) {
        urls.forEach(model.openURL)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let button = statusItem.button!
        button.image = NSImage(systemSymbolName: "ferry", accessibilityDescription: "Ferry")
        button.toolTip = "Ferry — Drop files here to send them to your phone"
        // The button's window forwards drag callbacks to its delegate (the NSDraggingDestination methods below).
        button.window?.registerForDraggedTypes([.fileURL])
        button.window?.delegate = self

        menu.delegate = self
        menu.autoenablesItems = false // items say themselves whether they're enabled
        statusItem.menu = menu

        NSApp.servicesProvider = self
        NSUpdateDynamicServices()

        if model.canNotify {
            let center = UNUserNotificationCenter.current()
            center.delegate = self
            model.registerCategories()
            center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }

        model.onChange = { [weak self] in self?.refreshLive() }
        updates.onChange = { [weak self] in self?.refreshUpdateItem() }
        model.onPaired = { [weak self] name in
            self?.pairWindow?.close()
            self?.model.notify("Paired with \(name)", "Open the Ferry menu to mirror its screen, read messages and send files.") {
                $0.threadIdentifier = "pairing"
            }
        }
    }

    // MARK: menu

    /// Ferry's version and Check for Updates… on top, then status (phones, now playing, transfers),
    /// then what you can do with the phone, then Pair, Settings, About and Quit.
    func menuNeedsUpdate(_ menu: NSMenu) {
        model.refreshPeers()
        menu.removeAllItems()
        let update = ClosureItem("") { [weak self] in self?.updates.check() }
        updateItem = update
        refreshUpdateItem()
        menu.items = [.sectionHeader(title: "Ferry \(Updates.version)"), update, .separator()]
        liveItems = []
        insertLive()
        let peers = model.peers, running = model.node != nil

        if peers.count == 1 {
            menu.addItem(.separator())
            phoneActions(peers[0], keys: true).forEach(menu.addItem)
        } else if peers.count > 1 {
            menu.addItem(.separator())
            for peer in peers { menu.addItem(submenu(peer.name, phoneActions(peer, keys: false)).symbol("iphone")) }
        }

        menu.addItem(.separator())
        let notRunning = model.startFailed ? "Ferry couldn't start" : "Ferry is still starting"
        menu.addItem(ClosureItem(peers.isEmpty ? "Pair Your Phone…" : "Pair Another Phone…", key: "n") { [weak self] in self?.showPairWindow() }
            .symbol("qrcode").enabled(running).tip(running ? nil : notRunning))
        menu.addItem(ClosureItem("Pair over USB…") { [weak self] in self?.model.pairOverUSB() }
            .symbol("cable.connector").enabled(running && !model.usbUnavailable)
            .tip(!running ? notRunning : model.usbUnavailable ? "Pairing over USB needs Android platform-tools (adb) on this Mac"
                : "Plug your phone into this Mac with a USB cable, then choose this to pair without scanning"))

        menu.addItem(.separator())
        menu.addItem(submenu("Settings", settingsItems()).symbol("gearshape"))
        menu.addItem(ClosureItem("About Ferry") { [weak self] in self?.showAbout() })
        menu.addItem(ClosureItem("Quit Ferry", key: "q") { NSApp.terminate(nil) })
    }

    func menuWillOpen(_ menu: NSMenu) { menuOpen = true }
    func menuDidClose(_ menu: NSMenu) { menuOpen = false }

    /// What you can do with one phone: at the top level with one phone, in its submenu with several.
    /// `keys`: give the main items keyboard equivalents (only one phone, so they're unambiguous).
    private func phoneActions(_ peer: FfiPeer, keys: Bool) -> [NSMenuItem] {
        let share = ClosureItem("Share Cursor") { [weak self] in
            guard let model = self?.model else { return }
            model.cursorPeer = model.cursorPeer == peer.id ? nil : peer.id
        }.symbol("cursorarrow.motionlines")
            .tip("Move the pointer past the \(model.cursorEdge.rawValue) edge of the screen to use it on \(peer.name). Press Escape to come back.")
        share.state = model.cursorPeer == peer.id ? .on : .off
        let dnd = ClosureItem("Do Not Disturb") { [weak self] in
            guard let model = self?.model else { return }
            model.setPhoneDnd(!(model.phoneDnd[peer.id] ?? false), peer: peer.id)
        }.symbol("moon").tip("Turn Do Not Disturb on or off on \(peer.name). Set Up Focus Sync in Settings makes the Mac follow too.")
        dnd.state = model.phoneDnd[peer.id] == true ? .on : .off
        let clip = model.clipboardHasText
        return [
            ClosureItem("Mirror Screen", key: keys ? "1" : "") { [weak self] in self?.model.mirror(peer) }.symbol("iphone")
                .tip("See and control \(peer.name)'s screen in a window"),
            ClosureItem("Messages", key: keys ? "2" : "") { [weak self] in self?.model.openMessages(peer) }.symbol("message")
                .unreadBadge(model.phoneTexts[peer.id]?.unread ?? 0),
            ClosureItem("Browse Files", key: keys ? "3" : "") { [weak self] in self?.model.openFiles(peer) }.symbol("folder"),
            .separator(),
            ClosureItem("Send Files…", key: keys ? "o" : "") { [weak self] in self?.model.chooseAndSend(to: peer) }.symbol("paperplane")
                .tip("You can also drop files on the Ferry icon in the menu bar"),
            ClosureItem("Send Clipboard") { [weak self] in self?.model.sendClipboard(to: peer.id) }.symbol("doc.on.clipboard")
                .enabled(clip).tip(clip ? "Send the text or link you copied to \(peer.name)" : "The clipboard has no text to send"),
            ClosureItem("Share Wi-Fi…") { [weak self] in self?.model.shareWifi(with: peer) }.symbol("wifi")
                .tip("Send this Mac's Wi-Fi network and its password to \(peer.name). Ferry asks first."),
            share,
            .separator(),
            ClosureItem("Ring Phone") { [weak self] in self?.model.ring(true, peer: peer.id) }.symbol("bell.and.waves.left.and.right")
                .tip("Rings \(peer.name) at full volume, even when it's on silent"),
            ClosureItem("Stop Ringing") { [weak self] in self?.model.ring(false, peer: peer.id) }.symbol("bell.slash"),
            dnd,
        ]
    }

    private func settingsItems() -> [NSMenuItem] {
        let hasPhone = !model.peers.isEmpty
        let sync = ClosureItem("Sync Clipboard") { [weak self] in self?.model.syncClipboard.toggle() }
            .tip(model.syncClipboard && model.clipboardDenied
                ? "Ferry can't read the clipboard. Allow it in System Settings → Privacy & Security → Paste from Other Apps."
                : "Send text you copy on this Mac to your phone automatically. Text copied on the phone always comes to the Mac.")
        sync.state = model.syncClipboard ? .on : .off
        let bluetooth = ClosureItem("Use Bluetooth") { [weak self] in self?.model.bluetoothEnabled.toggle() }
            .tip("Reach your phone over Bluetooth when it isn't on the same Wi-Fi")
        bluetooth.state = model.bluetoothEnabled ? .on : .off
        let edge = submenu("Cursor Crosses At", ScreenEdge.allCases.map { edge in
            let item = ClosureItem(edge == .left ? "Left Edge" : "Right Edge") { [weak self] in self?.model.cursorEdge = edge }
            item.state = model.cursorEdge == edge ? .on : .off
            return item
        }).enabled(hasPhone).tip(hasPhone ? "Which edge of the screen Share Cursor moves across" : "Pair a phone first")
        let login = ClosureItem("Open at Login") { [weak self] in self?.toggleLaunchAtLogin() }
        login.state = SMAppService.mainApp.status == .enabled ? .on : .off
        let autoUpdate = ClosureItem("Automatically Check for Updates") { [weak self] in self?.updates.automatic.toggle() }
            .enabled(updates.feed != nil)
            .tip(updates.feed != nil ? "Look for a new version of Ferry once a day" : "Updates aren't set up for this build yet")
        autoUpdate.state = updates.automatic ? .on : .off
        var items = [sync, bluetooth, edge,
                     ClosureItem("Set Up Focus Sync…") { [weak self] in self?.model.showFocusSetup() }
                         .enabled(hasPhone).tip(hasPhone ? "Have your phone follow this Mac's Focus" : "Pair a phone first"),
                     .separator(), login, autoUpdate]
        if hasPhone {
            items.append(.separator())
            items += model.peers.map { peer in ClosureItem("Forget \(peer.name)…") { [weak self] in self?.model.forget(peer) } }
        }
        return items
    }

    /// Refreshes the status block in place while the menu is open (progress, MB/s, battery).
    private func refreshLive() {
        guard menuOpen else { return } // menuNeedsUpdate rebuilds everything on the next open
        liveItems.forEach(menu.removeItem)
        insertLive()
    }

    /// "Check for Updates…", or the version a background check found. Enabled unless Sparkle is already busy.
    private func refreshUpdateItem() {
        guard let item = updateItem else { return }
        if let version = updates.available {
            item.title = "Update to Ferry \(version)…"
            _ = item.symbol("arrow.down.circle.fill").tip("See what's new and install it")
        } else {
            item.title = "Check for Updates…"
            _ = item.symbol("arrow.triangle.2.circlepath").tip(nil)
        }
        item.isEnabled = updates.canCheck
    }

    private func insertLive() {
        var items: [NSMenuItem] = []
        if model.node == nil {
            items.append(label(model.status).symbol(model.startFailed ? "exclamationmark.triangle" : "hourglass"))
            if model.startFailed { items.append(note("Quit Ferry and open it again.")) }
        } else if model.peers.isEmpty {
            items.append(label("No phone paired").symbol("iphone.slash"))
            items.append(note("Pair your phone to send files, share the clipboard, and see texts, calls and notifications on this Mac."))
        }
        for peer in model.peers {
            items.append(.sectionHeader(title: peer.name))
            let (link, symbol) = model.connection(peer.id)
            items.append(label(link).symbol(symbol))
            if let b = model.battery[peer.id] {
                items.append(label("Battery \(b.percent)%\(b.charging ? ", charging" : "")").symbol(batterySymbol(b.percent, b.charging)))
            }
            if let who = model.activeCalls[peer.id] {
                items.append(label("On a call with \(who)").symbol("phone.fill"))
                items.append(ClosureItem("Hang Up") { [weak self] in self?.model.callCommand(.decline, peer: peer.id) }
                    .symbol("phone.down.fill").indented())
            }
            if let np = model.nowPlaying[peer.id] {
                let playing = label(short(np.artist.isEmpty ? np.title : "\(np.title) — \(np.artist)")).symbol(np.playing ? "music.note" : "pause")
                playing.subtitle = np.app
                items.append(playing)
                items.append(ClosureItem("Previous Track") { [weak self] in self?.model.media(.previous, peer: peer.id) }
                    .symbol("backward.fill").indented())
                items.append(ClosureItem(np.playing ? "Pause" : "Play") { [weak self] in self?.model.media(.playPause, peer: peer.id) }
                    .symbol(np.playing ? "pause.fill" : "play.fill").indented())
                items.append(ClosureItem("Next Track") { [weak self] in self?.model.media(.next, peer: peer.id) }
                    .symbol("forward.fill").indented())
            }
        }
        if let note = model.bluetoothNote, model.bluetoothEnabled { items.append(label(note).symbol("exclamationmark.triangle")) }
        if model.usbLinks.contains(where: { !$0.serial.hasPrefix("ble:") && model.peer($0.peerId) == nil }) {
            items.append(label("A phone on USB isn't paired yet").symbol("cable.connector")
                .tip("Choose Pair over USB… to pair it"))
        }
        if !model.lines.isEmpty {
            items.append(.separator())
            items.append(.sectionHeader(title: "Recent Transfers"))
            items += model.lines.map { label(short($0.text)).symbol($0.symbol) }
        }
        let top = 3 // version, Check for Updates…, separator
        for (i, item) in items.enumerated() { menu.insertItem(item, at: top + i) }
        liveItems = items
    }

    private func label(_ text: String) -> NSMenuItem {
        let item = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    /// A short explanation in small text, wrapped to a sensible menu width.
    private func note(_ text: String) -> NSMenuItem {
        let item = label(text)
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byWordWrapping
        item.attributedTitle = NSAttributedString(string: wrap(text, 46), attributes: [
            .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize), .foregroundColor: NSColor.secondaryLabelColor, .paragraphStyle: style,
        ])
        return item
    }

    private func submenu(_ title: String, _ items: [NSMenuItem]) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.submenu = NSMenu()
        item.submenu?.autoenablesItems = false
        item.submenu?.items = items
        return item
    }

    private func batterySymbol(_ percent: UInt32, _ charging: Bool) -> String {
        if charging { return "battery.100percent.bolt" }
        return switch percent { case ..<13: "battery.0percent"; case ..<38: "battery.25percent"; case ..<63: "battery.50percent"
                                case ..<88: "battery.75percent"; default: "battery.100percent" }
    }

    private func showAbout() {
        NSApp.activate()
        let credits = NSAttributedString(string: "Core \(coreVersion())", attributes: [
            .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize), .foregroundColor: NSColor.secondaryLabelColor,
        ])
        NSApp.orderFrontStandardAboutPanel(options: [.credits: credits])
    }

    private func toggleLaunchAtLogin() {
        let service = SMAppService.mainApp
        do {
            if service.status == .enabled { try service.unregister() } else { try service.register() }
        } catch {
            logger.error("Open at Login: \(error, privacy: .public)")
            model.showAlert("Couldn't change Open at Login", "You can add or remove Ferry in System Settings → General → Login Items.")
        }
        if service.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
    }

    private func showPairWindow() {
        if let pairWindow, pairWindow.isVisible {
            NSApp.activate()
            return pairWindow.makeKeyAndOrderFront(nil)
        }
        let window = EditingWindow(contentViewController: NSHostingController(rootView: PairView(model: model) { [weak self] in
            self?.pairWindow?.close()
        }))
        window.title = "Pair Your Phone"
        window.styleMask = [.titled, .closable]
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
                model.showNotRunning()
            } else if model.peers.isEmpty {
                model.showAlert("No phone paired", "Choose Pair Your Phone… in the Ferry menu first.")
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

    /// Any app → Services → "Send link to phone" (NSMessage = sendLink): a web URL as a link, else text.
    @objc func sendLink(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let url = (pasteboard.readObjects(forClasses: [NSURL.self]) as? [URL])?.first
        model.sendText(pasteboard.string(forType: .string) ?? url?.absoluteString)
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

    /// Phone notifications ("n|…"): buttons, replies and dismisses go back to the phone.
    /// Texts ("s|…") and calls ("c|…"): reply, copy code, answer/decline; a click on a text opens its thread.
    /// Received-file notifications: body click and "Show in Finder" both reveal the files.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier, info = response.notification.request.content.userInfo
        let action = response.actionIdentifier, reply = (response as? UNTextInputNotificationResponse)?.userText ?? ""
        if id.hasPrefix(Notifications.smsPrefix) || id.hasPrefix(Notifications.callPrefix) {
            guard let peer = info[Notifications.peerKey] as? String else { return }
            let address = info[Notifications.addressKey] as? String ?? "", code = info[Notifications.codeKey] as? String ?? ""
            let thread = info[Notifications.threadKey] as? Int64
            await MainActor.run {
                model.textsResponse(peer: peer, action: action, reply: reply, address: address, code: code, thread: thread)
            }
            return
        }
        if id.hasPrefix(Notifications.phonePrefix) {
            guard let peer = info[Notifications.peerKey] as? String, let key = info[Notifications.keyKey] as? String else { return }
            await MainActor.run { model.phoneNotificationResponse(peer: peer, key: key, action: action, reply: reply) }
            return
        }
        if id == Notifications.update {
            if action != UNNotificationDismissActionIdentifier { await MainActor.run { updates.check() } }
            return
        }
        guard action != UNNotificationDismissActionIdentifier, let paths = info[Notifications.pathsKey] as? [String] else { return }
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

}

extension NSMenuItem {
    /// Unread texts on the Messages item (the ones Ferry has heard about).
    func unreadBadge(_ n: Int) -> Self {
        if n > 0 { badge = NSMenuItemBadge(count: n) }
        return self
    }

    func enabled(_ on: Bool) -> Self {
        isEnabled = on
        return self
    }

    func symbol(_ name: String) -> Self {
        image = NSImage(systemSymbolName: name, accessibilityDescription: nil)
        return self
    }

    func tip(_ text: String?) -> Self {
        toolTip = text
        return self
    }

    func indented() -> Self {
        indentationLevel = 1
        return self
    }
}

/// Menu titles grow the whole menu: long file names and song titles are cut in the middle.
func short(_ text: String, _ max: Int = 60) -> String {
    text.count <= max ? text : "\(text.prefix(max / 2 - 1))…\(text.suffix(max / 2 - 1))"
}

/// Hard-wraps `text` at word boundaries, for multi-line menu notes.
func wrap(_ text: String, _ width: Int) -> String {
    var lines: [String] = [], line = ""
    for word in text.split(separator: " ") {
        if !line.isEmpty, line.count + word.count + 1 > width { lines.append(line); line = "" }
        line += line.isEmpty ? String(word) : " \(word)"
    }
    return (lines + [line]).joined(separator: "\n")
}

/// The pairing QR (a single-use ticket, valid 10 minutes) and what to do on the phone.
struct PairView: View {
    let model: AppModel
    let close: () -> Void
    @State private var ticket: Result<String, Error>?
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 20) {
                qr.frame(width: 220, height: 220)
                VStack(alignment: .leading, spacing: 12) {
                    Text("On your phone").font(.headline)
                    step(1, "Install Ferry on your Android phone and open it.")
                    step(2, "Tap Scan QR and point the camera at this code.")
                    step(3, "Check that both screens show the same code, then confirm on both.")
                    Text("Your phone will see this Mac as “\(model.node?.deviceName() ?? "this Mac")”. Each code works once, for 10 minutes.")
                        .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                .frame(width: 250, alignment: .leading)
            }
            DisclosureGroup("Can't scan the code?") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Copy the pairing link and paste it into Ferry on your phone (Paste pairing link instead). "
                        + "Or plug the phone into this Mac with a USB cable and choose Pair over USB.")
                        .font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button(copied ? "Copied" : "Copy Pairing Link") {
                            guard case .success(let t) = ticket else { return }
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(t, forType: .string)
                            copied = true
                        }
                        .disabled((try? ticket?.get()) == nil)
                        Button("Pair over USB…") { model.pairOverUSB() }.disabled(model.usbUnavailable)
                    }
                }
                .padding(.top, 6)
            }
            HStack {
                Button("New Code") { newCode() }
                Spacer()
                Button("Done", action: close).keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 530)
        .background { Button("", action: close).keyboardShortcut(.cancelAction).opacity(0) } // Escape closes too
        .onAppear(perform: newCode)
        .onDisappear { model.refreshPeers() }
    }

    @ViewBuilder private var qr: some View {
        switch ticket {
        case .success(let t):
            if let image = qrImage(t) {
                Image(nsImage: image).interpolation(.none).resizable().accessibilityLabel("Pairing QR code")
            }
        case .failure:
            VStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle").font(.title)
                Text("Couldn't create a pairing code.").multilineTextAlignment(.center)
                Button("Try Again", action: newCode)
            }
            .foregroundStyle(.secondary)
        case nil:
            ProgressView()
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("\(n)").font(.callout.bold().monospacedDigit()).foregroundStyle(.secondary)
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func newCode() {
        ticket = model.newTicket()
        copied = false
        if case .failure(let e) = ticket { logger.error("pairing ticket: \(e, privacy: .public)") }
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
