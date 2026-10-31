import AppKit
import FerryCore
import FerryLogic
import UserNotifications

/// Phase 2 "everyday sync": phone notifications, clipboard, now playing, battery, ring, links.
extension AppModel {
    static let syncClipboardKey = "syncClipboard"

    var syncClipboard: Bool {
        get { UserDefaults.standard.bool(forKey: Self.syncClipboardKey) } // default off
        set { UserDefaults.standard.set(newValue, forKey: Self.syncClipboardKey) }
    }

    // MARK: incoming (via Bridge.onEvent)

    func handle(_ event: FfiEvent, from peer: String) {
        switch event {
        case let .notification(key, appName, package, title, text, _, buttons):
            postPhoneNotification(peer: peer, key: key, app: appName, package: package, title: title, text: text, buttons: buttons)
        case let .notificationRemoved(key):
            guard canNotify else { return }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [phoneID(peer, key)])
        case let .clipboard(text):
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(text, forType: .string)
            seenClipboardChange = pb.changeCount // our own write: don't send it back
        case let .nowPlaying(active, appName, title, artist, playing, _, _):
            nowPlaying[peer] = active ? NowPlaying(title: title, artist: artist, app: appName, playing: playing) : nil
            onChange()
        case let .battery(percent, charging):
            battery[peer] = (percent, charging)
            onChange()
        case let .link(url):
            guard let url = webURL(url) else { NSLog("Ferry: ignored non-web link from phone: \(url)"); return }
            NSWorkspace.shared.open(url)
            notify("Opened link from \(peerName(peer) ?? "your phone")", url.absoluteString)
        case .notificationAction, .mediaCommand, .ring:
            break // Mac → phone only
        }
    }

    // MARK: outgoing

    /// Sends to `peerIds` (default: every paired phone) off the main actor. `what` set = the user asked
    /// for it, so failures get an alert; nil = background sync, failures are only logged.
    func broadcast(_ event: FfiEvent, to peerIds: [String]? = nil, what: String? = nil) {
        Task {
            await ready() // Services can launch us
            refreshPeers()
            guard let node else {
                if what != nil { showAlert("Ferry isn't running", status) }
                return
            }
            let targets = peerIds ?? peers.map(\.id)
            if targets.isEmpty, what != nil {
                showAlert("No paired phone", "Choose Pair a device… in the Ferry menu first.")
            }
            let named = targets.map { ($0, peerName($0) ?? "phone") }
            // A send to an unreachable phone takes ~10 s to fail; keep it off the main actor.
            Task.detached {
                let errors: [String] = await withTaskGroup(of: String?.self) { group in
                    for (id, name) in named {
                        group.addTask {
                            do { try await node.sendEvent(peerId: id, event: event); return nil } catch {
                                NSLog("Ferry: sending \(what ?? "sync event") to \(name) failed: \(error)")
                                return "\(name): \(error)"
                            }
                        }
                    }
                    return await group.reduce(into: [String]()) { if let e = $1 { $0.append(e) } }
                }
                // Alert only if nothing got through: another paired phone being offline is normal.
                if let what, !errors.isEmpty, errors.count == named.count {
                    await self.showAlert("Couldn't send \(what)", errors.joined(separator: "\n"))
                }
            }
        }
    }

    /// Services "Send link to phone" and the menu's "Send clipboard to phone": a lone web URL goes as a
    /// link (the phone opens it), anything else as clipboard text.
    func sendText(_ text: String?) {
        guard let text, !text.isEmpty else { return showAlert("Nothing to send", "There's no text to send.") }
        if let url = webURL(text) { return broadcast(.link(url: url.absoluteString), what: "the link") }
        guard text.utf8.count <= clipboardLimit else { return showAlert("Too much text", "Ferry sends up to 1 MB of text.") }
        broadcast(.clipboard(text: text), what: "the text")
    }

    func sendClipboard() {
        sendText(NSPasteboard.general.string(forType: .string))
    }

    /// Every 1 s. Reading changeCount/types never triggers the macOS 15.4+ pasteboard-privacy alert;
    /// reading the string does (once, then System Settings → Privacy & Security → Paste from Other Apps).
    func pollClipboard() {
        let pb = NSPasteboard.general
        let change = pb.changeCount
        guard change != seenClipboardChange else { return }
        seenClipboardChange = change
        guard syncClipboard, !peers.isEmpty else { return }
        if #available(macOS 15.4, *), pb.accessBehavior == .alwaysDeny { return }
        let types = Set((pb.types ?? []).map(\.rawValue))
        guard types.isDisjoint(with: secretPasteboardTypes), // password managers
              let text = pb.string(forType: .string), !text.isEmpty, text.utf8.count <= clipboardLimit
        else { return }
        broadcast(.clipboard(text: text))
    }

    /// True when the user denied pasteboard access to Ferry (sync can't read anything).
    var clipboardDenied: Bool {
        if #available(macOS 15.4, *) { return NSPasteboard.general.accessBehavior == .alwaysDeny }
        return false
    }

    func media(_ cmd: FfiMediaCmd, peer: String) {
        broadcast(.mediaCommand(cmd: cmd), to: [peer])
    }

    func ring(_ start: Bool) {
        broadcast(.ring(start: start), what: start ? "Ring" : "Stop ringing")
    }

    // MARK: phone notifications

    private func phoneID(_ peer: String, _ key: String) -> String { "\(Notifications.phonePrefix)\(peer)|\(key)" }

    private func postPhoneNotification(
        peer: String, key: String, app: String, package: String, title: String, text: String, buttons: [FfiNotifButton]
    ) {
        guard canNotify else { return }
        let content = UNMutableNotificationContent()
        content.title = title.isEmpty ? app : "\(app): \(title)"
        content.body = text
        content.threadIdentifier = package
        content.categoryIdentifier = category(for: buttons)
        content.userInfo = [Notifications.peerKey: peer, Notifications.keyKey: key]
        let request = UNNotificationRequest(identifier: phoneID(peer, key), content: content, trigger: nil) // same id replaces
        Task {
            let center = UNUserNotificationCenter.current()
            _ = await center.notificationCategories() // waits for a just-set category, else the first one shows without buttons
            do { try await center.add(request) } catch { NSLog("Ferry: couldn't post notification: \(error)") }
        }
    }

    /// One category per distinct button set; `.customDismissAction` so a dismiss here dismisses on the phone.
    private func category(for buttons: [FfiNotifButton]) -> String {
        let id = categoryID(buttons.map { ($0.id, $0.title, $0.reply) })
        if categories[id] == nil {
            let actions = buttons.map { b -> UNNotificationAction in
                let aid = Notifications.buttonPrefix + b.id
                return b.reply
                    ? UNTextInputNotificationAction(identifier: aid, title: b.title, textInputButtonTitle: "Send", textInputPlaceholder: b.title)
                    : UNNotificationAction(identifier: aid, title: b.title)
            }
            // shortcut: the cache only grows (a handful of button sets per app); fine for a session.
            categories[id] = UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: [.customDismissAction])
            registerCategories()
        }
        return id
    }

    /// setNotificationCategories replaces the whole set, so always pass the union.
    func registerCategories() {
        guard canNotify else { return }
        let received = UNNotificationCategory(
            identifier: Notifications.received,
            actions: [UNNotificationAction(identifier: Notifications.showAction, title: "Show in Finder")],
            intentIdentifiers: []
        )
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values).union([received]))
    }

    /// From the notification delegate: button, reply or dismiss on a phone notification.
    func phoneNotificationResponse(peer: String, key: String, action: String, reply: String) {
        if action == UNNotificationDismissActionIdentifier {
            broadcast(.notificationAction(key: key, buttonId: "", replyText: "", dismiss: true), to: [peer])
        } else if action.hasPrefix(Notifications.buttonPrefix) {
            let button = String(action.dropFirst(Notifications.buttonPrefix.count))
            broadcast(.notificationAction(key: key, buttonId: button, replyText: reply, dismiss: false), to: [peer],
                      what: reply.isEmpty ? "the action" : "the reply")
        } // body click: nothing to do on the phone
    }

    func notify(_ title: String, _ body: String) {
        guard canNotify else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
