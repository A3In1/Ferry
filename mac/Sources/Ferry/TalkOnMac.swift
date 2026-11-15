import FerryHandsFree
import Foundation
import IOBluetooth
import UserNotifications

/// Trial "Talk on the Mac" (Settings, default off): while on, keeps a Bluetooth hands-free connection to the
/// phone (paired with this Mac over Classic Bluetooth) so calls can be answered and heard here. The HFP work is
/// in FerryHandsFree; this picks the phone, retries, and turns failures into one line for the menu.
@MainActor final class TalkOnMac {
    private(set) var link: HandsFreeLink?
    private var note: String?
    private var lastTry = Date.distantPast
    private var connectedSinceTry = false
    private let onChange: () -> Void

    init(onChange: @escaping () -> Void) { self.onChange = onChange }

    /// Calls can be answered here and their audio moved (the service-level connection is up).
    var ready: Bool { link?.connected == true }
    var audioOnMac: Bool { link?.audioOnMac == true }
    /// For the menu while the setting is on.
    var problem: String? { note ?? link?.problem }

    /// Every tick. `phoneName` (the Ferry peer's name) picks the Bluetooth device; nil or `!enabled` disconnects.
    // shortcut: one phone; with several, the first that matches by name (or the only hands-free gateway).
    func update(enabled: Bool, phoneName: String?) {
        guard enabled, let phoneName else { return stop() }
        guard HandsFreeLink.isSupported else { return say("This macOS no longer supports hands-free calls.") }
        // Paging a phone that's out of range is cheap but not free: once a minute.
        guard !ready, Date.now.timeIntervalSince(lastTry) >= 60 else { return }
        if let link, lastTry != .distantPast, !connectedSinceTry {
            say("Couldn't connect to \(link.name) for calls. On the phone, open Bluetooth settings → this Mac and turn on Phone calls.")
        }
        lastTry = .now
        connectedSinceTry = false
        guard let device = HandsFreeLink.findPhone(phoneName) else {
            dropLink()
            return say("Pair your phone with this Mac in Bluetooth settings first.")
        }
        if link?.device.addressString != device.addressString {
            dropLink()
            let made = HandsFreeLink(device: device)
            made.log = { logger.notice("hands-free: \($0, privacy: .public)") }
            made.onChange = { [weak self, weak made] in
                if made?.connected == true {
                    self?.note = nil
                    self?.connectedSinceTry = true
                }
                self?.onChange()
            }
            link = made
        }
        if link?.connect() == false { say("This macOS no longer supports hands-free calls.") }
    }

    private func say(_ text: String) {
        guard note != text else { return }
        note = text
        onChange()
    }

    private func dropLink() {
        link?.disconnect()
        link = nil
    }

    private func stop() {
        guard link != nil || note != nil else { return }
        dropLink()
        note = nil
        lastTry = .distantPast
        onChange()
    }
}

extension AppModel {
    /// Settings → "Talk on the Mac (trial)", default off.
    var talkOnMac: Bool {
        get { UserDefaults.standard.bool(forKey: "talkOnMac") }
        set {
            UserDefaults.standard.set(newValue, forKey: "talkOnMac")
            tick()
        }
    }

    func updateTalkOnMac() {
        talk.update(enabled: talkOnMac, phoneName: (preferredPeer ?? peers.first)?.name)
    }

    /// "Answer on Mac": the hands-free unit picks up, so the phone sends the audio here. Without the hands-free
    /// connection, Ferry answers on the phone and says why.
    func answerOnMac(peer: String) {
        if let link = talk.link, link.connected { return link.answer() }
        callCommand(.answer, peer: peer)
        notify("Answered on \(peerName(peer) ?? "your phone")",
               talk.problem ?? "Ferry isn't connected to it for calls yet, so the audio stays on the phone.")
    }

    /// "Answer on Phone" from a call notification while hands-free is up (else Android routes the audio here).
    func answerOnPhone(peer: String) {
        if let link = talk.link, link.connected { return link.answerOnPhone() }
        callCommand(.answer, peer: peer)
    }

    func moveCallAudio(toMac: Bool) {
        if toMac { talk.link?.audioToMac() } else { talk.link?.audioToPhone() }
    }
}
