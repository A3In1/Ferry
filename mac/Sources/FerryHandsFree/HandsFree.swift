import AVFoundation
import CoreAudio
import Foundation
import IOBluetooth

/// Trial "Talk on the Mac": the Mac as a Bluetooth hands-free unit (HFP "HF") for a phone paired over Classic
/// Bluetooth (the phone is the audio gateway, "AG"). Uses IOBluetooth's IOBluetoothHandsFreeDevice, which is
/// still in the SDK but may no longer work end to end on new macOS; `hfp-probe` exercises the same code.
/// IOBluetooth calls its delegate on the run loop of the thread that created the object: the main thread.
@MainActor public final class HandsFreeLink: NSObject {
    /// The class can vanish from a future IOBluetooth; nothing works then.
    public static var isSupported: Bool { NSClassFromString("IOBluetoothHandsFreeDevice") != nil }

    /// Classic Bluetooth devices this Mac is paired with (from System Settings → Bluetooth).
    public static func pairedDevices() -> [IOBluetoothDevice] {
        var seen = Set<String>() // macOS can list one device twice
        return (IOBluetoothDevice.pairedDevices() ?? []).compactMap { $0 as? IOBluetoothDevice }
            .filter { seen.insert($0.addressString ?? "").inserted }
    }

    /// Whether the device's cached SDP records offer the Hands-Free Audio Gateway service (UUID 0x111F).
    /// Uses what pairing cached; doesn't query the phone.
    public static func offersGateway(_ device: IOBluetoothDevice) -> Bool {
        if device.isHandsFreeAudioGateway { return true }
        guard let uuid = IOBluetoothSDPUUID(uuid16: 0x111F) else { return false }
        return device.getServiceRecord(for: uuid) != nil
    }

    /// The paired phone: an address ("aa-bb-…" or "aa:bb:…") or a name (case-insensitive, either containing the
    /// other); else the only paired device that offers the gateway service.
    public static func findPhone(_ query: String?) -> IOBluetoothDevice? {
        let paired = pairedDevices()
        if let q = query?.trimmingCharacters(in: .whitespaces).lowercased(), !q.isEmpty {
            let addr = q.replacingOccurrences(of: ":", with: "-")
            if let d = paired.first(where: { $0.addressString?.lowercased() == addr }) { return d }
            if let d = paired.first(where: {
                let n = ($0.name ?? "").lowercased()
                return !n.isEmpty && (n.contains(q) || q.contains(n))
            }) { return d }
        }
        let gateways = paired.filter(offersGateway)
        return gateways.count == 1 ? gateways[0] : nil
    }

    public let device: IOBluetoothDevice
    private var hf: IOBluetoothHandsFreeDevice?
    /// Every callback, for logs and the probe.
    public var log: (String) -> Void = { _ in }
    public var onChange: () -> Void = {}
    /// Copy audio between the call (SCO) and this Mac's mic and speakers while it's on the Mac.
    public var bridgeAudio = true

    /// Service-level connection up (AT commands work: answer, hang up, move audio).
    public private(set) var connected = false
    public private(set) var connecting = false
    /// The SCO audio link is open: the call's audio is on the Mac.
    public private(set) var audioOnMac = false
    public private(set) var callActive = false
    /// 0 none, 1 incoming ringing, 2 dialing, 3 remote alerted.
    public private(set) var callSetup = 0
    /// Something the user should know ("macOS didn't offer the call as a speaker…"), else nil.
    public private(set) var problem: String?
    private var bridge: CallAudio?

    public init(device: IOBluetoothDevice) {
        self.device = device
        super.init()
    }

    public var name: String { device.name ?? device.addressString ?? "the phone" }

    /// Opens the hands-free connection. Returns false if this macOS can't make an IOBluetoothHandsFreeDevice.
    @discardableResult public func connect() -> Bool {
        if hf == nil {
            guard Self.isSupported, let made = IOBluetoothHandsFreeDevice(device: device, delegate: self) as IOBluetoothHandsFreeDevice?
            else {
                log("IOBluetoothHandsFreeDevice is unavailable or init returned nil")
                return false
            }
            hf = made
            log("created IOBluetoothHandsFreeDevice for \(name) (supportedFeatures 0x\(String(made.supportedFeatures, radix: 16)))")
        }
        connecting = true
        log("connect()")
        hf?.connect()
        onChange()
        return true
    }

    public func disconnect() {
        stopBridge()
        log("disconnect()")
        hf?.disconnect()
        hf = nil
        connected = false
        connecting = false
        audioOnMac = false
        onChange()
    }

    public func answer() { log("acceptCall()"); hf?.acceptCall() }
    /// Picks up and moves the audio to the phone (with hands-free connected, Android otherwise routes it here).
    public func answerOnPhone() { log("acceptCallOnPhone()"); hf?.acceptCallOnPhone() }
    public func hangUp() { log("endCall()"); hf?.endCall() }
    public func audioToMac() { log("transferAudioToComputer()"); hf?.transferAudioToComputer() }
    public func audioToPhone() { log("transferAudioToPhone()"); hf?.transferAudioToPhone() }
    /// Lower level than audioToMac: opens SCO without asking the phone to move the call.
    public func openSCO() { log("connectSCO()"); hf?.connectSCO() }
    public func status() -> String {
        guard let hf else { return "no IOBluetoothHandsFreeDevice" }
        return "connected=\(hf.isConnected) sco=\(hf.isSCOConnected()) deviceFeatures=0x\(String(hf.deviceSupportedFeatures, radix: 16))"
            + " callActive=\(callActive) callSetup=\(callSetup) bridge=\(bridge != nil)"
    }

    // MARK: audio

    fileprivate func scoOpened(_ status: Int) {
        guard status == 0 else { return }
        audioOnMac = true
        problem = nil
        guard bridgeAudio else { return }
        Task { await startBridge() }
    }

    /// The SCO link shows up as a Core Audio device a moment after it opens; give it a few seconds.
    private func startBridge() async {
        var found: AudioDevice?
        for _ in 0..<10 {
            found = AudioDevice.matching(device)
            if found != nil || !audioOnMac { break }
            try? await Task.sleep(for: .milliseconds(500))
        }
        guard audioOnMac, bridge == nil else { return }
        guard let found else {
            log("no Core Audio device for \(name); devices now:\n" + AudioDevice.all().map { "  \($0)" }.joined(separator: "\n"))
            fail("This macOS no longer supports hands-free calls: the call's audio reached the Mac, but macOS didn't offer it as a microphone and speaker.")
            return
        }
        log("call audio device: \(found)")
        if await !AVCaptureDevice.requestAccess(for: .audio) {
            fail("Allow Ferry to use the microphone in System Settings → Privacy & Security → Microphone, so the caller can hear you.")
        }
        guard audioOnMac, bridge == nil else { return }
        do {
            bridge = try CallAudio(call: found, micAllowed: AVCaptureDevice.authorizationStatus(for: .audio) == .authorized, log: log)
        } catch {
            log("audio bridge failed: \(error)")
            fail("The call's audio reached the Mac, but Ferry couldn't play it (\(error.localizedDescription)).")
        }
    }

    private func stopBridge() {
        if bridge != nil { log("audio bridge stopped") }
        bridge?.stop()
        bridge = nil
    }

    private func fail(_ message: String) {
        problem = message
        log("problem: \(message)")
        onChange()
    }

    fileprivate func onMain(_ what: String, _ body: @escaping @MainActor () -> Void) {
        log(what)
        body()
        onChange()
    }
}

// The delegate methods are ObjC (nonisolated); IOBluetooth calls them on the main run loop, and anything
// else hops there. Only Sendable values (Ints, Strings) cross.
extension HandsFreeLink: IOBluetoothHandsFreeDeviceDelegate {
    nonisolated private func main(_ what: String, _ body: @escaping @MainActor @Sendable (HandsFreeLink) -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated { onMain(what) { body(self) } }
        } else {
            DispatchQueue.main.async { self.onMain(what + " (off main)") { body(self) } }
        }
    }

    nonisolated private static func int(_ n: NSNumber?) -> Int { n?.intValue ?? -1 }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFree!, connected status: NSNumber!) {
        let s = Self.int(status)
        main("connected status=\(s)") { $0.connecting = false; $0.connected = s == 0 }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFree!, disconnected status: NSNumber!) {
        let s = Self.int(status)
        main("disconnected status=\(s)") {
            $0.stopBridge()
            $0.connected = false
            $0.connecting = false
            $0.audioOnMac = false
        }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionOpened status: NSNumber!) {
        let s = Self.int(status)
        main("SCO audio opened status=\(s)") { $0.scoOpened(s) }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionClosed status: NSNumber!) {
        let s = Self.int(status)
        main("SCO audio closed status=\(s)") { $0.audioOnMac = false; $0.stopBridge() }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, isServiceAvailable n: NSNumber!) {
        main("indicator service=\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, isCallActive n: NSNumber!) {
        let v = Self.int(n)
        main("indicator call=\(v)") { $0.callActive = v == 1 }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, callSetupMode n: NSNumber!) {
        let v = Self.int(n)
        main("indicator callsetup=\(v)") { $0.callSetup = max(v, 0) }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, callHoldState n: NSNumber!) {
        main("indicator callheld=\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, signalStrength n: NSNumber!) {
        main("indicator signal=\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, isRoaming n: NSNumber!) {
        main("indicator roam=\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, batteryCharge n: NSNumber!) {
        main("indicator battchg=\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, incomingCallFrom number: String!) {
        main("incoming call from \(number ?? "?")") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, ringAttempt n: NSNumber!) {
        main("RING #\(Self.int(n))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, currentCall call: [AnyHashable: Any]!) {
        main("current call \(String(describing: call ?? [:]))") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, subscriberNumber number: String!) {
        main("subscriber number \(number ?? "?")") { _ in }
    }

    nonisolated public func handsFree(_ device: IOBluetoothHandsFreeDevice!, unhandledResultCode code: String!) {
        main("unhandled result code \(code ?? "?")") { _ in }
    }
}

/// Copies the call's audio both ways while it's on the Mac: the call device's input (the caller) to this Mac's
/// speakers, and this Mac's mic to the call device's output (the phone sends it to the caller). Two engines,
/// one per direction; nothing is written anywhere.
// shortcut: no echo cancellation; with speakers (not headphones) the caller may hear themselves.
@MainActor final class CallAudio {
    private let listen = AVAudioEngine(), talk = AVAudioEngine()

    init(call: AudioDevice, micAllowed: Bool, log: (String) -> Void) throws {
        // If macOS made the call device the default, use the built-in mic/speakers instead of looping it back.
        let speaker = AudioDevice.macSide(input: false, avoiding: call.id), mic = AudioDevice.macSide(input: true, avoiding: call.id)
        log("bridge: caller → \(speaker.map(\.name) ?? "default output"), \(mic.map(\.name) ?? "default input") → caller"
            + (micAllowed ? "" : " (mic not allowed: caller won't hear you)"))
        if call.inputs > 0 {
            try listen.inputNode.auAudioUnit.setDeviceID(call.id)
            if let speaker { try listen.outputNode.auAudioUnit.setDeviceID(speaker.id) }
            listen.connect(listen.inputNode, to: listen.mainMixerNode, format: listen.inputNode.outputFormat(forBus: 0))
            try listen.start()
        }
        if call.outputs > 0, micAllowed {
            if let mic { try talk.inputNode.auAudioUnit.setDeviceID(mic.id) }
            try talk.outputNode.auAudioUnit.setDeviceID(call.id)
            talk.connect(talk.inputNode, to: talk.mainMixerNode, format: talk.inputNode.outputFormat(forBus: 0))
            try talk.start()
        }
    }

    func stop() {
        listen.stop()
        talk.stop()
    }
}

/// A Core Audio device, as much as the trial needs.
public struct AudioDevice: Sendable, CustomStringConvertible {
    public let id: AudioDeviceID
    public let name, uid: String
    public let transport: UInt32
    public let inputs, outputs: Int

    public var isBluetooth: Bool {
        transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE
    }

    public var description: String {
        let t = withUnsafeBytes(of: transport.bigEndian) { String(decoding: $0, as: UTF8.self) }
        return "#\(id) \"\(name)\" uid=\(uid) transport=\(t) in=\(inputs) out=\(outputs)"
    }

    public static func all() -> [AudioDevice] {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids.map(AudioDevice.init)
    }

    init(_ id: AudioDeviceID) {
        self.id = id
        name = Self.string(id, kAudioObjectPropertyName)
        uid = Self.string(id, kAudioDevicePropertyDeviceUID)
        var t: UInt32 = 0, size = UInt32(MemoryLayout<UInt32>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyTransportType, mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        _ = AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &t)
        transport = t
        inputs = Self.channels(id, kAudioObjectPropertyScopeInput)
        outputs = Self.channels(id, kAudioObjectPropertyScopeOutput)
    }

    /// The phone's Bluetooth audio device with a microphone side (HFP, not A2DP): its UID usually carries the
    /// Bluetooth address, else match the name.
    static func matching(_ phone: IOBluetoothDevice) -> AudioDevice? {
        let addr = (phone.addressString ?? "").lowercased(), name = (phone.name ?? "").lowercased()
        let candidates = all().filter { $0.isBluetooth && $0.inputs > 0 }
        return candidates.first { !addr.isEmpty && $0.uid.lowercased().replacingOccurrences(of: ":", with: "-").contains(addr) }
            ?? candidates.first { !name.isEmpty && $0.name.lowercased().contains(name) }
    }

    /// The system default input/output unless it's `avoiding`; then the built-in one. nil = leave the default.
    static func macSide(input: Bool, avoiding: AudioDeviceID) -> AudioDevice? {
        var addr = AudioObjectPropertyAddress(
            mSelector: input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var id: AudioDeviceID = 0, size = UInt32(MemoryLayout<AudioDeviceID>.size)
        _ = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
        guard id == avoiding else { return nil }
        return all().first { $0.transport == kAudioDeviceTransportTypeBuiltIn && (input ? $0.inputs : $0.outputs) > 0 }
    }

    private static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String {
        var addr = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr, let value else { return "" }
        return value.takeRetainedValue() as String
    }

    private static func channels(_ id: AudioObjectID, _ scope: AudioObjectPropertyScope) -> Int {
        var addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration, mScope: scope,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(id, &addr, 0, nil, &size) == noErr, size > 0 else { return 0 }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, raw) == noErr else { return 0 }
        let list = UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self))
        return list.reduce(0) { $0 + Int($1.mNumberChannels) }
    }
}
