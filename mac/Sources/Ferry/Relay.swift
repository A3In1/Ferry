import AppKit
import FerryCore

/// Phase 7: the optional self-hosted internet relay (core/src/relay.rs). The core reads it at start.
extension AppModel {
    /// Host of the relay the running node uses, nil = off.
    var relayHost: String? { node?.relayUrl().map { URL(string: $0)?.host ?? $0 } }

    /// Menu "Internet relay…": edit the https URL, save it, then restart the node so it applies.
    func editRelay() {
        guard let node else { return }
        let current = node.relayUrl()
        let alert = NSAlert()
        alert.messageText = "Internet relay"
        alert.informativeText = "Reach your phone from a different network through your own relay server. The relay only passes encrypted data."
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 320, height: 24))
        field.stringValue = current ?? ""
        field.placeholderString = "https://relay.example.com"
        alert.accessoryView = field
        ["Save", "Turn off", "Cancel"].forEach { alert.addButton(withTitle: $0) }
        alert.window.initialFirstResponder = field
        // No Edit menu in a menu-bar app: give the field ⌘V and friends, like EditingWindow.
        let keys = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            MainActor.assumeIsolated { EditingWindow.editShortcut(e, from: nil) } ? nil : e
        }
        NSApp.activate()
        let choice = alert.runModal()
        keys.map(NSEvent.removeMonitor)
        let url: String?
        switch choice {
        case .alertFirstButtonReturn: url = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        case .alertSecondButtonReturn: url = nil
        default: return
        }
        guard (url?.isEmpty == false ? url : nil) != current else { return } // unchanged: no restart
        do { try node.setRelayUrl(url: url) } catch {
            showAlert("Couldn't save the relay", "\(error)")
            return
        }
        restartNode()
    }
}
