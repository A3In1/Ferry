import AppKit
import Darwin
import FerryCore
import FerryLogic

/// One `adb -s <serial> pull <phone path> <dest>`, on a pseudo-terminal: adb only prints its
/// progress lines to a terminal. Uses the adb the USB bridge found (`adbPath()`); plain pull only.
@MainActor final class AdbPull {
    enum Outcome { case done, cancelled, failed(String) }

    private let process = Process()
    private var master: Int32 = -1
    private var source: DispatchSourceRead?
    private var pending = "" // a line adb hasn't finished yet
    private var output = "" // tail, for the error message
    private(set) var cancelled = false

    /// Runs to the end; `onProgress` gets each progress line adb prints.
    func run(adb: String, serial: String, device: String, dest: URL, onProgress: @escaping (AdbProgress) -> Void) async -> Outcome {
        var slave: Int32 = -1
        var size = winsize(ws_row: 50, ws_col: 1000, ws_xpixel: 0, ws_ypixel: 0) // wide: adb elides lines to the width
        guard openpty(&master, &slave, nil, nil, &size) == 0 else { return .failed("no pseudo-terminal") }
        _ = fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK)
        let tty = FileHandle(fileDescriptor: slave, closeOnDealloc: true)
        process.executableURL = URL(filePath: adb)
        process.arguments = ["-s", serial, "pull", device, dest.path]
        var env = ProcessInfo.processInfo.environment
        env["TERM"] = "xterm-256color" // adb treats "dumb" (or none) as no terminal
        process.environment = env
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = tty
        process.standardError = tty

        let source = DispatchSource.makeReadSource(fileDescriptor: master, queue: .main)
        self.source = source
        source.setEventHandler { [weak self] in MainActor.assumeIsolated { self?.drain(onProgress) } }
        source.resume()

        let status: Int32 = await withCheckedContinuation { done in
            process.terminationHandler = { p in done.resume(returning: p.terminationStatus) }
            do {
                if cancelled { throw CancellationError() }
                try process.run()
            } catch {
                process.terminationHandler = nil
                done.resume(returning: -1)
            }
            try? tty.close() // the child has its copy; ours would keep the terminal open
        }
        drain(onProgress)
        source.cancel()
        close(master)
        if cancelled { return .cancelled }
        if status == 0 { return .done }
        return .failed(adbPullError(output) ?? "adb exited with status \(status)")
    }

    /// Cancel: stop adb (the caller deletes what it wrote).
    func cancel() {
        cancelled = true
        if process.isRunning { process.terminate() }
    }

    private func drain(_ onProgress: (AdbProgress) -> Void) {
        var buf = [UInt8](repeating: 0, count: 4096)
        while true {
            let n = read(master, &buf, buf.count)
            guard n > 0 else { break }
            let text = String(decoding: buf[..<n], as: UTF8.self)
            output = String((output + text).suffix(8192))
            pending += text
            // adb redraws its line with "\r"; finished lines end with "\n"
            var lines = pending.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\r" || $0 == "\n" })
            pending = lines.popLast().map(String.init) ?? ""
            for line in lines {
                if let p = AdbProgress.parse(String(line)) { onProgress(p) }
            }
        }
    }
}

extension AppModel {
    /// The adb serial of `peer`'s USB link (not a Bluetooth one), if it's plugged in and adb accepted it.
    func usbSerial(_ peer: String) -> String? {
        usbLinks.first { $0.peerId == peer && !$0.serial.hasPrefix("ble:") }?.serial
    }

    /// Downloads one phone item (file or folder) into Downloads with `adb pull`, with a progress row.
    /// Returns false if it didn't work (not readable by adb, unplugged…): the caller falls back to Ferry.
    func pullOverUsb(_ entry: FfiFsEntry, path: String, adb: String, serial: String, peer: String) async -> Bool {
        guard let device = adbDevicePath(path) else { return false }
        let fm = FileManager.default
        let downloads = fm.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        let name = freeName(entry.name, isDirectory: entry.dir) { fm.fileExists(atPath: downloads.appending(path: $0).path) }
        let dest = downloads.appending(path: name, directoryHint: entry.dir ? .isDirectory : .notDirectory)
        let t = LiveTransfer(id: "usb-\(newTransferID())", outgoing: false, peerName: peerName(peer), names: [name],
                             usb: UsbPullState(folder: entry.dir, knownSize: entry.dir ? nil : entry.size))
        let pull = AdbPull()
        t.onCancel = { pull.cancel() }
        begin(t)
        let measure = Task { [weak t] in
            while !Task.isCancelled, let t {
                let bytes = await Task.detached { diskSize(dest) }.value
                t.rawUsb?.onDisk(bytes)
                t.publish()
                try? await Task.sleep(for: .milliseconds(500))
            }
        }
        let outcome = await pull.run(adb: adb, serial: serial, device: device, dest: dest) { [weak t] p in
            t?.rawUsb?.update(p)
            t?.publish()
        }
        measure.cancel()
        switch outcome {
        case .done:
            await Task.detached { quarantineTree(dest) }.value
            end(t, .done("Downloaded"), line: "Downloaded \(name) over USB", symbol: "checkmark.circle")
            notifyReceived([dest], what: name, failed: 0, id: t.id)
            return true
        case .cancelled:
            try? fm.removeItem(at: dest) // a user cancel keeps nothing half-copied
            end(t, .cancelled("Cancelled"), line: "Cancelled downloading \(name)", symbol: "xmark.circle")
            return true
        case let .failed(why):
            logger.notice("adb pull of \(path, privacy: .private) failed, using Ferry instead: \(why, privacy: .public)")
            try? fm.removeItem(at: dest)
            end(t, .failed("Trying over the network…"), line: "Downloading \(name) over the network", symbol: "arrow.down.circle")
            return false
        }
    }
}

/// Bytes under `url` (a file, or a folder's files); runs off the main actor.
nonisolated func diskSize(_ url: URL) -> UInt64 {
    let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey]
    guard let values = try? url.resourceValues(forKeys: keys) else { return 0 }
    if values.isRegularFile == true { return UInt64(values.fileSize ?? 0) }
    var total: UInt64 = 0
    let walk = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys))
    while let f = walk?.nextObject() as? URL {
        if let v = try? f.resourceValues(forKeys: keys), v.isRegularFile == true { total += UInt64(v.fileSize ?? 0) }
    }
    return total
}

/// Marks a file like a browser download, so Gatekeeper checks it on first open.
nonisolated func quarantine(_ url: URL) {
    var url = url
    var values = URLResourceValues()
    values.quarantineProperties = [
        kLSQuarantineAgentNameKey as String: "Ferry",
        kLSQuarantineTypeKey as String: kLSQuarantineTypeOtherDownload as String,
    ]
    do { try url.setResourceValues(values) } catch { logger.error("couldn't quarantine \(url.path): \(error, privacy: .public)") }
}

/// Everything adb wrote.
nonisolated func quarantineTree(_ url: URL) {
    var urls = [url]
    if let walk = FileManager.default.enumerator(at: url, includingPropertiesForKeys: nil) {
        while let f = walk.nextObject() as? URL { urls.append(f) }
    }
    urls.forEach(quarantine)
}
