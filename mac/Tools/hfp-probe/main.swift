// hfp-probe: does this macOS still let the Mac be a Bluetooth hands-free unit for a paired phone?
// Run from Terminal (it asks for Bluetooth, and Microphone once call audio reaches the Mac). See mac/README.md.
import FerryHandsFree
import Foundation
import IOBluetooth

let start = Date.now
func say(_ s: String) {
    print(String(format: "[%7.2f] ", Date.now.timeIntervalSince(start)) + s)
    fflush(stdout)
}

let help = """
    usage: hfp-probe list                  paired Classic Bluetooth devices and Core Audio devices
           hfp-probe <phone> [command…]    open a prompt for the phone (name or address); commands run first

    commands: connect, disconnect, answer, hangup, audio-to-mac, audio-to-phone,
              sco (open audio without moving the call), bridge on|off (copy call audio to the Mac's
              mic/speakers, default on), devices, status, help, quit
    """

@MainActor func listPaired() {
    let paired = HandsFreeLink.pairedDevices()
    say("Hands-free class available: \(HandsFreeLink.isSupported)")
    say("\(paired.count) paired Classic Bluetooth device(s):")
    for d in paired {
        say("  \(d.name ?? "?")  \(d.addressString ?? "?")  connected=\(d.isConnected())  hfpGateway=\(HandsFreeLink.offersGateway(d))"
            + "  class=0x\(String(d.classOfDevice, radix: 16))")
    }
}

@MainActor func listAudio() {
    say("Core Audio devices:")
    AudioDevice.all().forEach { say("  \($0)") }
}

var args = Array(CommandLine.arguments.dropFirst())
guard let target = args.first, !["-h", "--help", "help"].contains(target) else {
    print(help)
    exit(0)
}
if target == "list" {
    listPaired()
    listAudio()
    exit(0)
}
args.removeFirst()

guard let phone = HandsFreeLink.findPhone(target) else {
    say("No paired device matches \"\(target)\". Pair the phone with this Mac in System Settings → Bluetooth first.")
    listPaired()
    exit(1)
}
say("phone: \(phone.name ?? "?") \(phone.addressString ?? "?") hfpGateway=\(HandsFreeLink.offersGateway(phone))")
let link = HandsFreeLink(device: phone)
link.log = { say("· " + $0) }
var lastProblem: String?
link.onChange = {
    if let p = link.problem, p != lastProblem { say("! " + p) }
    lastProblem = link.problem
}

@MainActor func run(_ line: String, _ link: HandsFreeLink) {
    let words = line.split(separator: " ").map(String.init)
    guard let cmd = words.first else { return }
    switch cmd {
    case "connect": if !link.connect() { say("! This macOS no longer supports hands-free calls (no IOBluetoothHandsFreeDevice).") }
    case "disconnect": link.disconnect()
    case "answer": link.answer()
    case "hangup": link.hangUp()
    case "audio-to-mac": link.audioToMac()
    case "audio-to-phone": link.audioToPhone()
    case "sco": link.openSCO()
    case "bridge":
        link.bridgeAudio = words.dropFirst().first != "off"
        say("bridge \(link.bridgeAudio ? "on" : "off") (applies the next time audio reaches the Mac)")
    case "devices": listAudio()
    case "status": say(link.status())
    case "help": print(help)
    case "quit", "exit":
        link.disconnect()
        exit(0)
    default: say("unknown command \"\(cmd)\" (try help)")
    }
}

for a in args { run(a, link) }
say("type a command (help for the list)")
// stdin on its own thread; commands run on the main thread, where IOBluetooth calls back.
let (lines, input) = AsyncStream.makeStream(of: String.self)
Thread {
    while let line = readLine() { input.yield(line.trimmingCharacters(in: .whitespaces)) }
    input.yield("quit")
}.start()
Task { @MainActor in
    for await line in lines { run(line, link) }
}
RunLoop.main.run()
