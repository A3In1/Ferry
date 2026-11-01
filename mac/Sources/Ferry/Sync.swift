import AppKit
import FerryCore
import FerryLogic
import UserNotifications

/// Phase 2 "everyday sync": phone notifications, clipboard, now playing, battery, ring, links.
extension AppModel {
    static let syncClipboardKey = "syncClipboard"

    var syncClipboard: Bool {
        get { UserDefaults.standard.bool(forKey: Self.syncClipboardKey) } // default off
        set {
            UserDefaults.standard.set(newValue, forKey: Self.syncClipboardKey)
            updateClipboardPoll()
        }
    }

    /// Starts or stops the poll to match `syncClipboard`. Turning it on only picks up changes made from then on.
    func updateClipboardPoll() {
        guard syncClipboard != (clipboardPoll != nil) else { return }
        clipboardPoll?.cancel()
        clipboardPoll = nil
        guard syncClipboard else { return }
        seenClipboardChange = NSPasteboard.general.changeCount
        clipboardPoll = Task { while (try? await Task.sleep(for: .seconds(1), tolerance: .milliseconds(500))) != nil { pollClipboard() } }
    }

    // MARK: incoming (via Bridge.onEvent)

    func handle(_ event: FfiEvent, from peer: String) {
        lastHeard[peer] = .now
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
            guard let url = webURL(url) else { return logger.notice("ignored non-web link from phone: \(url)") }
            NSWorkspace.shared.open(url)
            notify("Opened a link from \(peerName(peer) ?? "your phone")", url.absoluteString) { $0.threadIdentifier = "link" }
        case .smsThreads, .smsMessages, .smsNew, .smsRead, .call, .callLog:
            handleTexts(event, from: peer) // Messages.swift
        case let .fsList(path, entries, error):
            handleFiles(path: path, entries: entries, error: error, from: peer) // Files.swift
        case let .dnd(on):
            phoneDndChanged(on, peer: peer) // WifiFocus.swift
        case .notificationAction, .mediaCommand, .ring, .smsThreadsRequest, .smsMessagesRequest, .smsSend, .smsMarkRead, .callCommand,
             .callLogRequest, .fsListRequest, .fsGet, .wifiNetwork:
            break // Mac → phone only
        }
    }

    // MARK: outgoing

    /// Sends to `peerIds` (default: every paired phone) off the main actor. `failure` set = the user asked
    /// for it, so failures get an alert opening with that sentence ("The message wasn't sent."); nil =
    /// background sync, failures are only logged.
    func broadcast(_ event: FfiEvent, to peerIds: [String]? = nil, failure: String? = nil) {
        Task {
            await ready() // Services can launch us
            refreshPeers()
            guard let node else {
                if failure != nil { showNotRunning() }
                return
            }
            let targets = peerIds ?? peers.map(\.id)
            if targets.isEmpty, failure != nil {
                showAlert("No phone paired", "Choose Pair Your Phone… in the Ferry menu first.")
            }
            let named = targets.map { ($0, peerName($0) ?? "phone") }
            // A send to an unreachable phone takes ~10 s to fail; keep it off the main actor.
            Task.detached {
                let errors: [String] = await withTaskGroup(of: String?.self) { group in
                    for (id, name) in named {
                        group.addTask {
                            do { try await node.sendEvent(peerId: id, event: event); return nil } catch {
                                logger.error("sending (\(failure ?? "sync", privacy: .public)) to \(name) failed: \(error, privacy: .public)")
                                return name
                            }
                        }
                    }
                    return await group.reduce(into: [String]()) { if let e = $1 { $0.append(e) } }
                }
                // Alert only if nothing got through: another paired phone being offline is normal.
                if let failure, !errors.isEmpty, errors.count == named.count {
                    let one = errors.count == 1
                    let hint = one ? await self.reachHint(errors[0]) : "Make sure your phones are nearby and running Ferry, then try again."
                    await self.showAlert(one ? "Couldn't reach \(errors[0])" : "Couldn't reach your phones", "\(failure) \(hint)")
                }
            }
        }
    }

    /// Services "Send link to phone" and the menu's "Send Clipboard": a lone web URL goes as a
    /// link (the phone opens it), anything else as clipboard text. `peers` nil = every paired phone.
    func sendText(_ text: String?, to peers: [String]? = nil) {
        guard let text, !text.isEmpty else { showAlert("Nothing to send", "Select or copy some text first."); return }
        if let url = webURL(text) { return broadcast(.link(url: url.absoluteString), to: peers, failure: "The link wasn't sent.") }
        guard text.utf8.count <= clipboardLimit else {
            showAlert("The text is too long", "Ferry can send up to 1 MB of text at a time.")
            return
        }
        broadcast(.clipboard(text: text), to: peers, failure: "The text wasn't sent.")
    }

    func sendClipboard(to peer: String) {
        sendText(NSPasteboard.general.string(forType: .string), to: [peer])
    }

    /// The clipboard has text (checks types only, so it never triggers the paste-privacy prompt).
    var clipboardHasText: Bool { NSPasteboard.general.types?.contains(.string) == true }

    /// Every 1 s while syncing. Reading changeCount/types never triggers the macOS 15.4+ pasteboard-privacy alert;
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

    func ring(_ start: Bool, peer: String) {
        broadcast(.ring(start: start), to: [peer], failure: start ? "The phone didn't ring." : "The phone didn't stop ringing.")
    }

    // MARK: phone notifications

    private func phoneID(_ peer: String, _ key: String) -> String { "\(Notifications.phonePrefix)\(peer)|\(key)" }

    private func postPhoneNotification(
        peer: String, key: String, app: String, package: String, title: String, text: String, buttons: [FfiNotifButton]
    ) {
        guard canNotify else { return }
        let category = category(for: buttons)
        // Title: who/what; subtitle: the app (and phone, with several). Grouped per phone and app.
        let phone = peers.count > 1 ? peerName(peer) : nil
        notify(title.isEmpty ? app : title, text, id: phoneID(peer, key), waitForCategories: true) {
            $0.subtitle = [title.isEmpty ? nil : app, phone].compactMap(\.self).joined(separator: " · ")
            $0.threadIdentifier = "n|\(peer)|\(package)"
            $0.categoryIdentifier = category
            $0.userInfo = [Notifications.peerKey: peer, Notifications.keyKey: key]
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
        UNUserNotificationCenter.current().setNotificationCategories(Set(categories.values).union([received] + textCategories))
    }

    /// From the notification delegate: button, reply or dismiss on a phone notification.
    func phoneNotificationResponse(peer: String, key: String, action: String, reply: String) {
        if action == UNNotificationDismissActionIdentifier {
            broadcast(.notificationAction(key: key, buttonId: "", replyText: "", dismiss: true), to: [peer])
        } else if action.hasPrefix(Notifications.buttonPrefix) {
            let button = String(action.dropFirst(Notifications.buttonPrefix.count))
            broadcast(.notificationAction(key: key, buttonId: button, replyText: reply, dismiss: false), to: [peer],
                      failure: reply.isEmpty ? "The action wasn't sent to the phone." : "Your reply wasn't sent.")
        } // body click: nothing to do on the phone
    }
}
