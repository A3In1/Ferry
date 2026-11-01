import AppKit
import FerryCore
import FerryLogic
import SwiftUI
import UserNotifications

/// Phase 5: one phone's texts and calls. The phone is the source of truth; this only holds what it last sent.
@MainActor @Observable final class PhoneTexts {
    enum Feed { case messages, calls }
    struct Pending: Identifiable {
        let id = UUID()
        let address, body: String
    }

    var threads: [FfiSmsThread] = []
    var messages: [Int64: [FfiSmsMessage]] = [:] // by thread id, oldest first
    var pending: [Pending] = [] // sent here, waiting for the phone's SmsNew echo
    var calls: [FfiCallEntry] = []
    var selected: Int64?
    var composeTo: String? // "New message": "" while typing, the number once sent (until its thread shows up)
    var showCalls = false
    var silent: Set<Feed> = [] // asked, nothing came back within 5 s
    var loaded: Set<Feed> = [] // the phone has answered at least once (empty lists are "none yet", not "loading")
    @ObservationIgnored var heard: [Feed: Int] = [:]

    func thread(_ id: Int64?) -> FfiSmsThread? { threads.first { $0.threadId == id } }
}

func displayName(_ name: String, _ number: String) -> String {
    !name.isEmpty ? name : !number.isEmpty ? number : "Unknown"
}

extension AppModel {
    func texts(_ peer: String) -> PhoneTexts { phoneTexts.value(peer, orInsert: PhoneTexts.init) }

    func openMessages(_ peer: FfiPeer) {
        showWindow(\.messageWindows, for: peer) { MessagesWindow(model: self, peer: peer) }
    }

    /// Asks the phone; if nothing on that feed comes back within 5 s, the window says how to turn it on.
    private func ask(_ event: FfiEvent, _ feed: PhoneTexts.Feed, peer: String) {
        let t = texts(peer)
        ifUnanswered({ t.heard[feed, default: 0] }) { t.silent.insert(feed) }
        broadcast(event, to: [peer])
    }

    // shortcut: limit 0 = the phone's defaults (50 threads/calls, 100 messages), no paging; add "load older" if people ask.
    func refreshTexts(_ peer: String) {
        ask(.smsThreadsRequest(limit: 0), .messages, peer: peer)
        ask(.callLogRequest(limit: 0), .calls, peer: peer)
        if let id = texts(peer).selected { openThread(id, peer: peer) }
    }

    func openThread(_ id: Int64, peer: String) {
        let t = texts(peer)
        // shortcut: read state stays on the Mac, the phone still counts it unread; send it once the protocol has a mark-read event.
        if let i = t.threads.firstIndex(where: { $0.threadId == id }) { t.threads[i].unread = 0 }
        let thread = textThread(peer, id)
        removeNotifications { $0.request.content.threadIdentifier == thread } // read here: banners are stale
        ask(.smsMessagesRequest(threadId: id, limit: 0), .messages, peer: peer)
    }

    /// Only on an explicit Send/Return or a notification reply. Shown as "sending…" until the echo.
    // shortcut: a send that never echoes (phone offline, SMS failed) stays "sending…"; add a timeout or a phone-side ack when it matters.
    func sendSms(_ body: String, to address: String, peer: String) {
        let body = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, !address.isEmpty else { return }
        texts(peer).pending.append(.init(address: address, body: body))
        broadcast(.smsSend(address: address, body: body), to: [peer], failure: "The message wasn't sent.")
    }

    func callCommand(_ cmd: FfiCallCmd, peer: String) {
        broadcast(.callCommand(cmd: cmd), to: [peer], failure: cmd == .answer ? "The call wasn't answered." : "The call wasn't ended.")
    }

    // MARK: from the phone (Sync.swift routes these here)

    func handleTexts(_ event: FfiEvent, from peer: String) {
        let t = texts(peer)
        switch event {
        case let .smsThreads(threads):
            heard(t, .messages)
            t.threads = threads
        case let .smsMessages(id, messages):
            heard(t, .messages)
            t.messages[id] = messages
        case let .smsNew(m):
            heard(t, .messages)
            received(m, peer: peer)
        case let .callLog(calls):
            heard(t, .calls)
            t.calls = calls
        case let .call(state, number, name):
            call(state, who: displayName(name, number), peer: peer)
        default:
            break
        }
    }

    private func heard(_ t: PhoneTexts, _ feed: PhoneTexts.Feed) {
        t.heard[feed, default: 0] += 1
        t.silent.remove(feed)
        t.loaded.insert(feed)
    }

    /// Notification thread for one conversation on one phone.
    private func textThread(_ peer: String, _ thread: Int64) -> String { "sms|\(peer)|\(thread)" }

    private func received(_ m: FfiSmsMessage, peer: String) {
        let t = texts(peer)
        if m.outgoing, let i = t.pending.firstIndex(where: { $0.body == m.body && sameNumber($0.address, m.address) }) {
            t.pending.remove(at: i)
        }
        if var list = t.messages[m.threadId], !list.contains(where: { $0.id == m.id }) {
            list.append(m)
            t.messages[m.threadId] = list
        }
        let viewing = t.selected == m.threadId && messageWindows[peer]?.window.isKeyWindow == true
        var thread = t.thread(m.threadId)
            ?? FfiSmsThread(threadId: m.threadId, address: m.address, name: m.name, snippet: "", dateMs: 0, unread: 0)
        thread.snippet = m.body
        thread.dateMs = m.dateMs
        if !m.outgoing, !viewing { thread.unread += 1 }
        t.threads.removeAll { $0.threadId == m.threadId }
        t.threads.insert(thread, at: 0)
        if m.outgoing, let to = t.composeTo, sameNumber(to, m.address) {
            t.composeTo = nil
            t.selected = m.threadId
        }
        if !m.outgoing, !viewing { notifyText(m, peer: peer) }
    }

    private func notifyText(_ m: FfiSmsMessage, peer: String) {
        let sender = displayName(m.name, m.address)
        let code = oneTimeCode(in: m.body)
        let phone = peers.count > 1 ? peerName(peer) : nil
        notify(sender, m.body, id: "\(Notifications.smsPrefix)\(peer)|\(m.id)") {
            $0.subtitle = [code.map { "Code \($0)" }, phone].compactMap(\.self).joined(separator: " · ")
            $0.threadIdentifier = self.textThread(peer, m.threadId)
            $0.categoryIdentifier = code == nil ? Notifications.sms : Notifications.smsCode
            $0.userInfo = [Notifications.peerKey: peer, Notifications.addressKey: m.address,
                           Notifications.threadKey: m.threadId, Notifications.codeKey: code ?? ""]
        }
    }

    private func call(_ state: FfiCallState, who: String, peer: String) {
        let id = Notifications.callPrefix + peer
        if canNotify { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id]) }
        activeCalls[peer] = state == .active ? who : nil
        onChange()
        switch state {
        case .ringing:
            notify("\(who) is calling", "Call audio stays on \(peerName(peer) ?? "your phone").", id: id) {
                $0.sound = .default
                // Time-sensitive only breaks through Focus with the entitlement; without it macOS shows it as active.
                $0.interruptionLevel = .timeSensitive
                $0.categoryIdentifier = Notifications.call
                $0.userInfo = [Notifications.peerKey: peer]
            }
        case .idle:
            if messageWindows[peer] != nil { ask(.callLogRequest(limit: 0), .calls, peer: peer) }
        case .active:
            break
        }
    }

    // MARK: notifications

    var textCategories: [UNNotificationCategory] {
        let reply = UNTextInputNotificationAction(identifier: Notifications.replyAction, title: "Reply", options: [],
                                                  textInputButtonTitle: "Send", textInputPlaceholder: "Message")
        let copy = UNNotificationAction(identifier: Notifications.copyAction, title: "Copy Code")
        let answer = UNNotificationAction(identifier: Notifications.answerAction, title: "Answer")
        let decline = UNNotificationAction(identifier: Notifications.declineAction, title: "Decline", options: [.destructive])
        return [
            UNNotificationCategory(identifier: Notifications.sms, actions: [reply], intentIdentifiers: []),
            UNNotificationCategory(identifier: Notifications.smsCode, actions: [copy, reply], intentIdentifiers: []),
            UNNotificationCategory(identifier: Notifications.call, actions: [answer, decline], intentIdentifiers: []),
        ]
    }

    /// From the notification delegate: a text or call notification's action (or a click on a text).
    func textsResponse(peer: String, action: String, reply: String, address: String, code: String, thread: Int64?) {
        switch action {
        case Notifications.answerAction: callCommand(.answer, peer: peer)
        case Notifications.declineAction: callCommand(.decline, peer: peer)
        case Notifications.replyAction: sendSms(reply, to: address, peer: peer)
        case Notifications.copyAction:
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(code, forType: .string)
            seenClipboardChange = pb.changeCount // don't sync the code back to the phone
        case UNNotificationDefaultActionIdentifier:
            guard let thread, let p = self.peer(peer) else { return }
            texts(peer).composeTo = nil
            texts(peer).selected = thread
            openMessages(p)
        default:
            break
        }
    }
}

/// "<Phone> — Messages": threads (or recent calls) on the left, the selected conversation on the right.
final class MessagesWindow: PeerWindow {
    init(model: AppModel, peer: FfiPeer) {
        super.init(model: model, peer: peer, kind: "Messages", size: NSSize(width: 780, height: 560), minSize: NSSize(width: 640, height: 420))
        host(MessagesView(model: model, peer: peer.id, texts: model.texts(peer.id)))
        model.refreshTexts(peer.id)
        present()
    }
}

struct MessagesView: View {
    let model: AppModel
    let peer: String
    @Bindable var texts: PhoneTexts
    @State private var draft = ""
    @State private var number = ""
    @FocusState private var focus: Field?
    private enum Field { case number, draft }

    var body: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                Picker("Show", selection: $texts.showCalls) {
                    Text("Messages").tag(false)
                    Text("Calls").tag(true)
                }
                .pickerStyle(.segmented).labelsHidden().padding(8)
                if let hint, !(texts.showCalls ? texts.calls.isEmpty : texts.threads.isEmpty) {
                    Text(hint).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
                }
                Group { if texts.showCalls { callList } else { threadList } }
                    .overlay { listPlaceholder }
            }
            .navigationSplitViewColumnWidth(min: 220, ideal: 270)
        } detail: {
            if texts.composeTo != nil {
                VStack(spacing: 0) {
                    HStack {
                        Text("To:")
                        TextField("Phone number", text: $number).textFieldStyle(.roundedBorder)
                            .focused($focus, equals: .number)
                            .onSubmit { focus = .draft }
                    }
                    .padding(10)
                    Divider()
                    conversation(nil, address: number)
                }
                .onAppear { focus = .number }
                .onExitCommand { texts.composeTo = nil } // Escape cancels a new message
            } else if let t = texts.thread(texts.selected) {
                VStack(spacing: 0) {
                    Text(displayName(t.name, t.address)).font(.headline).padding(10)
                    Divider()
                    conversation(texts.messages[t.threadId], address: t.address)
                }
            } else {
                ContentUnavailableView("No Conversation Selected", systemImage: "message",
                                       description: Text("Choose a conversation, or start a new message."))
            }
        }
        .toolbar {
            Button { model.refreshTexts(peer) } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                .help("Refresh (⌘R)")
                .keyboardShortcut("r")
            Button {
                texts.selected = nil
                texts.composeTo = ""
                number = ""
            } label: { Label("New Message", systemImage: "square.and.pencil") }
                .help("New Message (⌘N)")
                .keyboardShortcut("n")
        }
        .onChange(of: texts.selected) { _, id in
            guard let id else { return }
            texts.composeTo = nil
            model.openThread(id, peer: peer)
        }
        .frame(minWidth: 640, minHeight: 420)
    }

    private var hint: String? {
        if texts.showCalls { return texts.silent.contains(.calls) ? "Turn on Calls in Ferry on your phone to see them here." : nil }
        return texts.silent.contains(.messages) ? "Turn on Messages in Ferry on your phone to see your texts here." : nil
    }

    /// Shown over an empty list: why it's empty, or that it's still loading.
    @ViewBuilder private var listPlaceholder: some View {
        let feed: PhoneTexts.Feed = texts.showCalls ? .calls : .messages
        if texts.showCalls ? texts.calls.isEmpty : texts.threads.isEmpty {
            if let hint {
                Text(hint).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding()
            } else if !texts.loaded.contains(feed) {
                ProgressView(texts.showCalls ? "Loading calls…" : "Loading conversations…").controlSize(.small)
            } else {
                Text(texts.showCalls ? "No recent calls" : "No conversations yet").font(.callout).foregroundStyle(.secondary)
            }
        }
    }

    private var threadList: some View {
        List(texts.threads, id: \.threadId, selection: $texts.selected) { t in
            HStack(alignment: .top, spacing: 6) {
                Circle().fill(t.unread > 0 ? Color.accentColor : .clear).frame(width: 8, height: 8).padding(.top, 5)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(displayName(t.name, t.address)).fontWeight(t.unread > 0 ? .semibold : .regular).lineLimit(1)
                        Spacer()
                        Text(shortDate(t.dateMs)).font(.caption).foregroundStyle(.secondary)
                    }
                    Text(t.snippet).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityValue(t.unread > 0 ? "Unread" : "")
        }
    }

    private var callList: some View {
        List(Array(texts.calls.enumerated()), id: \.offset) { _, c in
            let missed = c.kind == .missed
            HStack {
                Image(systemName: missed ? "phone.down.fill" : c.kind == .outgoing ? "phone.arrow.up.right" : "phone.arrow.down.left")
                    .foregroundStyle(missed ? Color.red : Color.secondary)
                    .frame(width: 18)
                    .accessibilityLabel(missed ? "Missed call" : c.kind == .outgoing ? "Outgoing call" : "Incoming call")
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName(c.name, c.number)).foregroundStyle(missed ? Color.red : Color.primary).lineLimit(1)
                    Text(missed ? "Missed" : Duration.seconds(c.durationS).formatted(.time(pattern: .minuteSecond)))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(shortDate(c.dateMs)).font(.caption).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        }
    }

    /// `messages` nil = not loaded yet (or a new conversation).
    private func conversation(_ messages: [FfiSmsMessage]?, address: String) -> some View {
        let pending = texts.pending.filter { sameNumber($0.address, address) }
        return VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        if messages == nil, texts.composeTo == nil { ProgressView("Loading messages…").controlSize(.small).padding() }
                        ForEach(messages ?? [], id: \.id) { bubble($0.body, outgoing: $0.outgoing) }
                        ForEach(pending) { bubble($0.body, outgoing: true, note: "Sending…") }
                        Color.clear.frame(height: 1).id("end")
                    }
                    .padding(12)
                }
                .onChange(of: (messages?.count ?? 0) + pending.count, initial: true) { proxy.scrollTo("end", anchor: .bottom) }
            }
            Divider()
            HStack {
                TextField("Text message", text: $draft).textFieldStyle(.roundedBorder)
                    .focused($focus, equals: .draft)
                    .onSubmit { send(to: address) }
                Button("Send") { send(to: address) }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || address.isEmpty)
            }
            .padding(10)
        }
    }

    private func bubble(_ text: String, outgoing: Bool, note: String? = nil) -> some View {
        HStack {
            if outgoing { Spacer(minLength: 60) }
            VStack(alignment: outgoing ? .trailing : .leading, spacing: 2) {
                Text(text)
                    .textSelection(.enabled)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .foregroundStyle(outgoing ? Color.white : Color.primary)
                    .background(outgoing ? Color.accentColor : Color.secondary.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
                if let note { Text(note).font(.caption2).foregroundStyle(.secondary) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(outgoing ? "You: \(text)" : text)
            .accessibilityValue(note ?? "")
            if !outgoing { Spacer(minLength: 60) }
        }
    }

    private func send(to address: String) {
        let address = address.trimmingCharacters(in: .whitespaces)
        guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !address.isEmpty else { return }
        if texts.composeTo != nil {
            if let t = texts.threads.first(where: { sameNumber($0.address, address) }) {
                texts.selected = t.threadId
            } else {
                texts.composeTo = address
            }
        }
        model.sendSms(draft, to: address, peer: peer)
        draft = ""
    }

    private func shortDate(_ ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        return Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened) : d.formatted(date: .abbreviated, time: .omitted)
    }
}
