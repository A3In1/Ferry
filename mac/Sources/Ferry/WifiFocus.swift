import AppKit
import FerryLogic
import CoreLocation
import CoreWLAN
import FerryCore

/// Only asked for authorization: macOS hides the Wi-Fi name from apps without Location access.
@MainActor private let locationManager = CLLocationManager()

/// Phase 6: "Share Wi-Fi…" and Do Not Disturb sync.
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
            broadcast(.wifiNetwork(ssid: ssid, password: password), to: [peer.id], failure: "The Wi-Fi network wasn't shared.")
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
            showAlert("This Mac isn't on Wi-Fi", "Connect to a Wi-Fi network, then choose Share Wi-Fi again.")
        }
    }

    /// Menu toggle "Do Not Disturb" for one phone. The check mark only moves when the phone reports back.
    func setPhoneDnd(_ on: Bool, peer: String) {
        broadcast(.dnd(on: on), to: [peer], failure: "Do Not Disturb wasn't changed.")
    }

    /// `ferry://focus?on=1|0` from the user's Shortcuts Focus automation: Mac Focus → phone.
    func openURL(_ url: URL) {
        guard let on = focusState(from: url) else { return logger.info("ignored URL \(url, privacy: .private)") }
        Task {
            await ready() // the URL can launch us
            refreshPeers()
            // Phones already matching are skipped: usually our own "Ferry Focus On/Off" Shortcut echoing their change.
            let targets = peers.map(\.id).filter { phoneDnd[$0, default: false] != on }
            guard !targets.isEmpty else { return }
            broadcast(.dnd(on: on), to: targets)
        }
    }

    func showFocusSetup() {
        showAlert("Have your phone follow your Mac's Focus",
                  "In the Shortcuts app, open Automation and add two Focus automations for Do Not Disturb "
                      + "(or the Focus you use):\n\n• When turning on: Open URL ferry://focus?on=1\n"
                      + "• When turning off: Open URL ferry://focus?on=0\n\n"
                      + "Turn off “Ask Before Running” for both. Ferry then turns the phone's Do Not Disturb on and off "
                      + "to match. On the phone, turn on Do Not Disturb in Ferry.")
    }

    /// The phone's Do Not Disturb changed: run the user's "Ferry Focus On/Off" Shortcut, if there is one.
    // shortcut: two quick changes can run their Shortcuts out of order; queue them if that shows up.
    func phoneDndChanged(_ on: Bool, peer: String) {
        phoneDnd[peer] = on
        onChange()
        let name = on ? "Ferry Focus On" : "Ferry Focus Off"
        Task {
            let list = await runTool("/usr/bin/shortcuts", ["list"])
            if list.out.split(separator: "\n").contains(where: { $0 == name }) {
                if await !runTool("/usr/bin/shortcuts", ["run", name]).ok { logger.error("Shortcut \"\(name, privacy: .public)\" failed") }
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
