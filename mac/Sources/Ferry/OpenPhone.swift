import AppKit
import FerryCore

/// "Use Phone": the phone's own screen in a window on the Mac, through the open-source scrcpy
/// (Apache-2.0, credited in About). Ferry only runs the copy installed with Homebrew.
///
/// Works over the USB cable, or over Wi-Fi once the cable has set wireless up (Android drops wireless
/// on every restart, so the first use after a restart needs the cable). The phone's screen stays dark
/// while the window is open and locks when it closes.
extension AppModel {
    private static let scrcpyPaths = ["/opt/homebrew/bin/scrcpy", "/usr/local/bin/scrcpy"]
    private static let brewPaths = ["/opt/homebrew/bin/brew", "/usr/local/bin/brew"]
    private static let wirelessKey = "usePhoneWireless" // [peer id: "ip:port"], remembered across launches

    func usePhone(_ peer: FfiPeer) {
        MacAuth.confirm("use your phone on this Mac") { [weak self] ok in
            if ok { self?.usePhoneAuthed(peer) }
        }
    }

    private func usePhoneAuthed(_ peer: FfiPeer) {
        if let running = phoneWindows[peer.id], running.isRunning {
            running.terminate() // a second click closes it
            return
        }
        guard let adb = adbPath() else {
            showAlert("One-time setup needed", "Use Phone needs Android platform-tools. Install them, then try again.")
            return
        }
        guard Self.scrcpyPaths.contains(where: FileManager.default.isExecutableFile) else {
            return installPhoneTool { [weak self] in self?.usePhoneAuthed(peer) }
        }
        Task {
            // Over the cable: Ferry's own USB link if it knows one, else the only phone on adb. Then
            // remember the Wi-Fi address for next time.
            var cabled = usbSerial(peer.id)
            if cabled == nil { cabled = await singleUsbSerial(adb: adb) }
            if let serial = cabled {
                // Launch over the cable, and set wireless up only once the window closes (switching to
                // wireless restarts adb and would kill a running session).
                launchPhone(peer, serial: serial, adb: adb, over: "USB")
                return
            }
            // No cable: reconnect over the remembered Wi-Fi address.
            guard let endpoint = wirelessEndpoint(peer.id) else {
                showAlert("Plug in \(peer.name) once",
                          "Connect \(peer.name) with the USB cable the first time (and allow USB debugging). "
                              + "After that, Use Phone works over Wi-Fi until the phone restarts.")
                return
            }
            guard await adbConnect(endpoint, adb: adb) else {
                forgetWireless(peer.id)
                showAlert("Can't reach \(peer.name) over Wi-Fi",
                          "Plug \(peer.name) in with the USB cable once more, then try Use Phone again. "
                              + "Make sure the phone and this Mac are on the same Wi-Fi.")
                return
            }
            launchPhone(peer, serial: endpoint, adb: adb, over: "Wi-Fi")
        }
    }

    /// The one phone on adb over a cable (state "device", not Wi-Fi), when there's exactly one, so Use
    /// Phone works even before Ferry's own USB bridge has attached it.
    private func singleUsbSerial(adb: String) async -> String? {
        guard let out = await adbRun(adb, ["devices"]) else { return nil }
        let cabled = out.split(separator: "\n").dropFirst().compactMap { line -> String? in
            let parts = line.split(whereSeparator: { $0 == "\t" || $0 == " " })
            guard parts.count >= 2, parts[1] == "device" else { return nil }
            let serial = String(parts[0])
            return serial.contains(":") ? nil : serial // skip a Wi-Fi entry
        }
        return cabled.count == 1 ? cabled[0] : nil
    }

    private func launchPhone(_ peer: FfiPeer, serial: String, adb: String, over: String) {
        guard let tool = Self.scrcpyPaths.first(where: FileManager.default.isExecutableFile) else { return }
        let p = Process()
        p.executableURL = URL(filePath: tool)
        // No --turn-screen-off: on some phones (vivo) forcing the screen off blanks the mirror too,
        // especially on the lock screen.
        p.arguments = ["-s", serial, "--window-title", peer.name, "--power-off-on-close",
                       "--stay-awake", "--shortcut-mod=lsuper,rsuper"]
        var env = ProcessInfo.processInfo.environment
        env["ADB"] = adb // the adb Ferry already uses
        p.environment = env
        p.standardOutput = FileHandle.nullDevice
        let errPipe = Pipe()
        p.standardError = errPipe
        let started = Date.now
        p.terminationHandler = { [weak self] p in
            let status = p.terminationStatus
            let err = String(decoding: errPipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            Task { @MainActor in
                guard let self else { return }
                logger.notice("use phone: window closed (\(status, privacy: .public))")
                self.phoneWindows[peer.id] = nil
                self.onChange()
                // Failed almost at once (not a window the user closed): say why.
                if status != 0, Date.now.timeIntervalSince(started) < 3 { return self.phoneFailed(peer, err) }
                // Window closed after a real USB session: set wireless up for next time (it restarts adb,
                // so it's safe only now that nothing is using the connection).
                if over == "USB" {
                    var cablePresent = self.usbSerial(peer.id) != nil
                    if !cablePresent { cablePresent = await self.singleUsbSerial(adb: adb) != nil }
                    if cablePresent { await self.rememberWireless(peer.id, usbSerial: serial, adb: adb) }
                }
            }
        }
        do {
            try p.run()
            phoneWindows[peer.id] = p
            logger.notice("use phone: opened \(peer.name, privacy: .public) over \(over, privacy: .public)")
            onChange() // the menu item becomes "Close"
        } catch {
            showAlert("Couldn't open \(peer.name)", error.localizedDescription)
        }
    }

    /// Turns scrcpy's failure into a message that says what to do.
    private func phoneFailed(_ peer: FfiPeer, _ err: String) {
        if err.contains("unauthorized") {
            showAlert("Allow USB debugging on \(peer.name)",
                      "Unlock \(peer.name) and tap Allow on the “Allow USB debugging?” popup (tick “Always allow from this computer”). "
                          + "If there's no popup, unplug and replug the cable, then try Use Phone again.")
        } else if err.contains("no devices") || err.contains("not found") {
            showAlert("Can't reach \(peer.name)",
                      "Plug \(peer.name) in with the USB cable, or make sure it's on the same Wi-Fi, then try Use Phone again.")
        } else {
            let detail = err.split(separator: "\n").first { $0.contains("ERROR") }.map(String.init) ?? "It closed right after opening."
            showAlert("Couldn't open \(peer.name)", detail)
        }
    }

    // MARK: Wi-Fi address

    func wirelessEndpoint(_ peer: String) -> String? {
        (UserDefaults.standard.dictionary(forKey: Self.wirelessKey) as? [String: String])?[peer]
    }

    private func setWirelessEndpoint(_ peer: String, _ value: String?) {
        var map = (UserDefaults.standard.dictionary(forKey: Self.wirelessKey) as? [String: String]) ?? [:]
        map[peer] = value
        UserDefaults.standard.set(map, forKey: Self.wirelessKey)
    }

    private func forgetWireless(_ peer: String) { setWirelessEndpoint(peer, nil) }

    /// Puts the USB-attached phone into wireless mode and records its `ip:5555`, so a later call can
    /// reconnect without the cable. Best effort: failures just mean the cable is needed next time.
    private func rememberWireless(_ peer: String, usbSerial: String, adb: String) async {
        _ = await adbRun(adb, ["-s", usbSerial, "tcpip", "5555"])
        guard let out = await adbRun(adb, ["-s", usbSerial, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"]),
              let ip = Self.firstIPv4(out) else { return }
        setWirelessEndpoint(peer, "\(ip):5555")
        logger.notice("use phone: \(peer, privacy: .public) can reconnect over Wi-Fi at \(ip, privacy: .private):5555")
    }

    private func adbConnect(_ endpoint: String, adb: String) async -> Bool {
        guard let out = await adbRun(adb, ["connect", endpoint]) else { return false }
        return out.contains("connected to") // "connected to …" or "already connected to …"
    }

    /// First IPv4 in `ip addr` output, e.g. "inet 192.168.1.5/24 …" → "192.168.1.5".
    static func firstIPv4(_ out: String) -> String? {
        for line in out.split(separator: "\n") where line.contains("inet ") {
            if let after = line.split(separator: "inet ").last,
               let addr = after.split(separator: "/").first, addr.contains(".") {
                return String(addr).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private func adbRun(_ adb: String, _ args: [String]) async -> String? {
        await Task.detached {
            let p = Process()
            p.executableURL = URL(filePath: adb)
            p.arguments = args
            let pipe = Pipe()
            p.standardOutput = pipe
            p.standardError = FileHandle.nullDevice
            guard (try? p.run()) != nil else { return nil }
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            p.waitUntilExit()
            return String(decoding: data, as: UTF8.self)
        }.value
    }

    /// One-time install of the screen tool with Homebrew, after asking.
    private func installPhoneTool(then: @escaping @MainActor () -> Void) {
        guard let brew = Self.brewPaths.first(where: FileManager.default.isExecutableFile) else {
            showAlert("One-time setup needed",
                      "Use Phone needs Homebrew (brew.sh). Install it, then run “brew install scrcpy” in Terminal and try again.")
            return
        }
        guard confirm("One-time setup", "Use Phone needs a free component (about 50 MB). Install it now?", "Install") else { return }
        logger.notice("use phone: installing the screen tool")
        Task {
            let ok = await Task.detached {
                let p = Process()
                p.executableURL = URL(filePath: brew)
                p.arguments = ["install", "scrcpy"]
                p.standardOutput = FileHandle.nullDevice
                p.standardError = FileHandle.nullDevice
                guard (try? p.run()) != nil else { return false }
                p.waitUntilExit()
                return p.terminationStatus == 0
            }.value
            if ok { then() } else { showAlert("Setup didn't finish", "Try “brew install scrcpy” in Terminal, then Use Phone again.") }
        }
    }
}
