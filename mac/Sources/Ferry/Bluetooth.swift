import CoreBluetooth
import FerryCore
import FerryLogic
import Foundation

/// Phase 3: the Mac as a BLE peripheral. Advertises the rotating presence UUID (no local name), serves
/// the L2CAP PSM on a fixed GATT service and carries the core's session over each L2CAP channel the
/// phone opens. All CoreBluetooth calls and state live on `queue`; L2CAP streams run on a StreamPump thread.
final class Bluetooth: NSObject, CBPeripheralManagerDelegate, @unchecked Sendable {
    private let serviceUUID = CBUUID(string: "F377E501-0000-1000-8000-00805F9B34FB")
    private let psmUUID = CBUUID(string: CBUUIDL2CAPPSMCharacteristicString)

    private let node: FerryNode
    /// Short menu line ("Bluetooth off", …) or nil when there's nothing to say.
    private let onNote: @Sendable (String?) -> Void
    private let queue = DispatchQueue(label: "dev.ferry.bluetooth")
    private var manager: CBPeripheralManager?
    private var psm: CBL2CAPPSM?
    private var advertised: String?
    private var timer: DispatchSourceTimer?
    /// Open channels; holding the CBL2CAPChannel keeps it open.
    private var links: [ObjectIdentifier: Link] = [:]

    init(node: FerryNode, onNote: @escaping @Sendable (String?) -> Void) {
        self.node = node
        self.onNote = onNote
    }

    /// Idempotent; AppModel calls it every tick (on while a phone is paired and the toggle is on).
    func setWanted(_ on: Bool) {
        queue.async { [self] in
            if on, manager == nil { start() } else if !on, manager != nil { stop() }
        }
    }

    // MARK: queue

    private func start() {
        // Creating the manager is what asks for Bluetooth permission (first time only).
        manager = CBPeripheralManager(delegate: self, queue: queue,
                                      options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
        let t = DispatchSource.makeTimerSource(queue: queue)
        // The UUID rotates every ~15 min; polling each minute catches the change promptly.
        t.schedule(deadline: .now() + 60, repeating: 60)
        t.setEventHandler { [weak self] in self?.advertise() }
        t.resume()
        timer = t
    }

    private func stop() {
        timer?.cancel()
        timer = nil
        if let manager {
            manager.stopAdvertising()
            if let psm { manager.unpublishL2CAPChannel(psm) }
            manager.removeAllServices()
            manager.delegate = nil
        }
        links.values.forEach { $0.finish() }
        manager = nil
        psm = nil
        advertised = nil
        onNote(nil)
        NSLog("Ferry: Bluetooth stopped")
    }

    /// (Re)starts advertising when the presence UUID changed (or after power-on).
    private func advertise() {
        guard let manager, manager.state == .poweredOn else { return }
        let uuid = node.presenceBleUuid()
        guard uuid != advertised else { return }
        advertised = uuid
        manager.stopAdvertising()
        // No local name: the 128-bit UUID fills the advert, and a name would identify the Mac.
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: uuid)]])
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard peripheral === manager else { return }
        NSLog("Ferry: Bluetooth state \(peripheral.state.rawValue)")
        switch peripheral.state {
        case .poweredOn:
            onNote(nil)
            // A power cycle drops services (and maybe channels); set everything up again.
            if let psm { peripheral.unpublishL2CAPChannel(psm) }
            psm = nil
            advertised = nil
            peripheral.removeAllServices()
            let service = CBMutableService(type: serviceUUID, primary: true)
            // value nil = dynamic: reads come to didReceiveRead (the PSM changes on every publish).
            service.characteristics = [CBMutableCharacteristic(type: psmUUID, properties: .read,
                                                                value: nil, permissions: .readable)]
            peripheral.add(service)
            // Unencrypted: Android can't open a secure channel without bonding; QUIC/TLS secures it.
            peripheral.publishL2CAPChannel(withEncryption: false)
            advertise()
        case .poweredOff: onNote("Bluetooth off")
        case .unauthorized: onNote("Allow Bluetooth for Ferry in System Settings")
        case .unsupported: onNote("Bluetooth unavailable")
        default: onNote(nil)
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: (any Error)?) {
        if let error { NSLog("Ferry: Bluetooth advertising failed: \(error)") } else {
            NSLog("Ferry: Bluetooth advertising \(advertised ?? "?")")
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: (any Error)?) {
        if let error { NSLog("Ferry: adding the Bluetooth GATT service failed: \(error)") }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: (any Error)?) {
        if let error { return NSLog("Ferry: publishing the L2CAP channel failed: \(error)") }
        psm = PSM
        NSLog("Ferry: L2CAP PSM \(PSM)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == psmUUID, let psm else {
            return peripheral.respond(to: request, withResult: .unlikelyError)
        }
        let value = withUnsafeBytes(of: psm.littleEndian) { Data($0) }
        guard request.offset <= value.count else { return peripheral.respond(to: request, withResult: .invalidOffset) }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: (any Error)?) {
        guard let channel, error == nil else { return NSLog("Ferry: L2CAP channel failed to open: \(String(describing: error))") }
        let label = "ble:\(channel.peer.identifier.uuidString.prefix(8))"
        NSLog("Ferry: L2CAP channel open (\(label))")
        // shortcut: unbounded buffer between radio and core; fine at BLE rates (≤100 KB/s) into a local pipe.
        let (chunks, input) = AsyncStream.makeStream(of: Data.self)
        let pump = StreamPump(input: channel.inputStream, output: channel.outputStream,
                              onRead: { input.yield($0) }, onClose: { input.finish() })
        let link = Link(channel: channel, pump: pump)
        let id = ObjectIdentifier(link)
        links[id] = link
        let node = node
        Task {
            // The core replaces an older bridge for the same phone (and closes its sink), so a
            // reconnect needs nothing extra here.
            let bridge = await node.openStreamBridge(label: label, sink: link)
            Task {
                do {
                    let peer = try await bridge.waitPeer()
                    // The bridge hello is only a routing hint; still, don't carry traffic for strangers.
                    guard node.peers().contains(where: { $0.id == peer }) else {
                        NSLog("Ferry: closing L2CAP channel from an unpaired device")
                        return link.finish()
                    }
                    NSLog("Ferry: Bluetooth bridge up (\(label))")
                } catch {
                    NSLog("Ferry: Bluetooth bridge hello failed (\(label)): \(error)")
                    link.finish()
                }
            }
            for await chunk in chunks {
                do { try await bridge.push(data: chunk) } catch { break }
            }
            await bridge.finish()
            link.finish()
            NSLog("Ferry: L2CAP channel closed (\(label))")
            self.queue.async { self.links[id] = nil }
        }
    }
}

/// One L2CAP channel as the core's ByteSink.
private final class Link: ByteSink, @unchecked Sendable {
    let channel: CBL2CAPChannel
    let pump: StreamPump

    init(channel: CBL2CAPChannel, pump: StreamPump) {
        self.channel = channel
        self.pump = pump
    }

    func write(data: Data) -> Bool { pump.write(data) }
    func finish() { pump.close() }
}
