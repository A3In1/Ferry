import AppKit
import CoreLocation
import CoreWLAN
import FerryCore

/// Only asked for authorization: macOS hides the Wi-Fi name from apps without Location access.
@MainActor private let locationManager = CLLocationManager()

/// Phase 6: "Share Wi-Fi with <phone>" and Do Not Disturb sync.
extension AppModel {
    /// The password comes from the System keychain through `security`, which shows macOS's own prompt. It's never logged.
    func shareWifi(with peer: FfiPeer) {
        guard let ssid = CWWiFiClient.shared().interface()?.ssid(), !ssid.isEmpty else { return explainNoSSID() }
        guard confirm("Send Wi-Fi “\(ssid)” and its password to \(peer.name)?",
                      "macOS may ask for your password to read it from the keychain. The phone asks before adding the network.",
                      "Send") else { return }
        Task {
            let r = await runTool("/usr/bin/security", ["find-generic-password", "-D", "AirPort network password", "-a", ssid, "-w"])
            var password = r.ok && r.out.hasSuffix("\n") ? String(r.out.dropLast()) : r.out
            if !r.ok || password.isEmpty {
                guard confirm("Couldn't read the password for “\(ssid)”",
                              "It isn't in the System keychain, or the prompt was cancelled. Send “\(ssid)” to \(peer.name) "
                                  + "as an open network with no password?", "Send without password") else { return }
                password = ""
            }
            broadcast(.wifiNetwork(ssid: ssid, password: password), to: [peer.id], what: "the Wi-Fi network")
        }
    }

    private func explainNoSSID() {
        switch locationManager.authorizationStatus {
        case .notDetermined where CLLocationManager.locationServicesEnabled():
            guard confirm("Allow Location for Ferry",
                          "macOS only tells apps the name of your Wi-Fi network if they have Location access. Ferry doesn't use "
                              + "your location. Click Allow in the next prompt, then choose Share Wi-Fi again.", "Continue") else { return }
            locationManager.requestWhenInUseAuthorization()
        case .notDetermined, .denied, .restricted:
            guard confirm("Ferry can't see the Wi-Fi name",
                          "macOS only tells apps the name of your Wi-Fi network if they have Location access. Turn on Location "
                              + "Services and allow Ferry in System Settings → Privacy & Security → Location Services.",
                          "Open System Settings") else { return }
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices")!)
        default:
            showAlert("Not on Wi-Fi", "This Mac isn't connected to a Wi-Fi network.")
        }
    }

    private func confirm(_ title: String, _ text: String, _ ok: String) -> Bool {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = text
        alert.addButton(withTitle: ok)
        alert.addButton(withTitle: "Cancel")
        NSApp.activate()
        return alert.runModal() == .alertFirstButtonReturn
    }

    /// Menu toggle "Phone Do Not Disturb". The check mark only moves when the phone reports back.
    func setPhoneDnd(_ on: Bool) {
        broadcast(.dnd(on: on), what: on ? "Do Not Disturb on" : "Do Not Disturb off")
    }

    /// The phone's Do Not Disturb changed: run the user's "Ferry Focus On/Off" Shortcut, if there is one.
    // shortcut: two quick changes can run their Shortcuts out of order; queue them if that shows up.
    func phoneDndChanged(_ on: Bool) {
        phoneDnd = on
        onChange()
        let name = on ? "Ferry Focus On" : "Ferry Focus Off"
        Task {
            let list = await runTool("/usr/bin/shortcuts", ["list"])
            if list.out.split(separator: "\n").contains(where: { $0 == name }) {
                if await !runTool("/usr/bin/shortcuts", ["run", name]).ok { NSLog("Ferry: Shortcut \"\(name)\" failed") }
            } else if !UserDefaults.standard.bool(forKey: "focusHintShown") {
                UserDefaults.standard.set(true, forKey: "focusHintShown")
                showAlert("Have your Mac follow the phone's Do Not Disturb",
                          "Your phone turned Do Not Disturb \(on ? "on" : "off"). To make the Mac follow, open the Shortcuts app and "
                              + "create two shortcuts:\n\n• “Ferry Focus On”: add the Set Focus action, set Do Not Disturb to On.\n"
                              + "• “Ferry Focus Off”: add the Set Focus action, set it to turn Do Not Disturb Off.\n\n"
                              + "Ferry runs them whenever the phone changes. This hint is shown once.")
            }
        }
    }
}

/// Runs a command-line tool off the main actor: exit status 0, and stdout. stderr is dropped.
private func runTool(_ path: String, _ args: [String]) async -> (ok: Bool, out: String) {
    await Task.detached {
        let p = Process()
        p.executableURL = URL(filePath: path)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { return (false, "") }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return (p.terminationStatus == 0, String(decoding: data, as: UTF8.self))
    }.value
}
