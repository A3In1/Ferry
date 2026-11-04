import AppKit
import FerryCore

/// "Open Phone": the phone's screen in its own window over USB, lock screen included, through the
/// open-source scrcpy (Apache-2.0, credited in About). Ferry only runs the copy installed with Homebrew;
/// the phone's screen goes dark while it's open and locks when the window closes.
extension AppModel {
    private static let scrcpyPaths = ["/opt/homebrew/bin/scrcpy", "/usr/local/bin/scrcpy"]
    private static let brewPaths = ["/opt/homebrew/bin/brew", "/usr/local/bin/brew"]

    func openPhone(_ peer: FfiPeer) {
        MacAuth.confirm("open your phone on this Mac") { [weak self] ok in
            if ok { self?.openPhoneConfirmed(peer) }
        }
    }

    private func openPhoneConfirmed(_ peer: FfiPeer) {
        if let running = phoneWindows[peer.id], running.isRunning {
            return logger.notice("open phone: already open for \(peer.name, privacy: .public)")
        }
        guard let serial = usbSerial(peer.id), let adb = adbPath() else {
            showAlert("Plug in \(peer.name)",
                      "Open Phone works over the USB cable. Plug the phone in and allow USB debugging on it, then try again.")
            return
        }
        guard let tool = Self.scrcpyPaths.first(where: FileManager.default.isExecutableFile) else {
            return installPhoneTool { [weak self] in self?.openPhoneConfirmed(peer) }
        }
        let p = Process()
        p.executableURL = URL(filePath: tool)
        p.arguments = ["-s", serial, "--window-title", peer.name, "--turn-screen-off", "--power-off-on-close",
                       "--stay-awake", "--shortcut-mod=lcmd,rcmd"]
        var env = ProcessInfo.processInfo.environment
        env["ADB"] = adb // the adb Ferry already uses for USB
        p.environment = env
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        p.terminationHandler = { p in
            let status = p.terminationStatus
            Task { @MainActor in logger.notice("open phone: window closed (\(status, privacy: .public))") }
        }
        do {
            try p.run()
            phoneWindows[peer.id] = p
            logger.notice("open phone: opened \(peer.name, privacy: .public) over USB")
        } catch {
            showAlert("Couldn't open \(peer.name)", error.localizedDescription)
        }
    }

    /// One-time install of the screen tool with Homebrew, after asking.
    private func installPhoneTool(then: @escaping @MainActor () -> Void) {
        guard let brew = Self.brewPaths.first(where: FileManager.default.isExecutableFile) else {
            showAlert("One-time setup needed",
                      "Open Phone needs Homebrew (brew.sh). Install it, then run “brew install scrcpy” in Terminal and try again.")
            return
        }
        guard confirm("One-time setup", "Open Phone needs a free component (about 50 MB). Install it now?", "Install") else { return }
        logger.notice("open phone: installing the screen tool")
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
            if ok { then() } else { showAlert("Setup didn't finish", "Try “brew install scrcpy” in Terminal, then Open Phone again.") }
        }
    }
}
