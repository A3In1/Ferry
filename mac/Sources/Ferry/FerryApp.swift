import AppKit
import CoreImage.CIFilterBuiltins
import FerryCore
import SwiftUI

@main
struct FerryApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        MenuBarExtra("Ferry", systemImage: "ferry") {
            MenuContent(model: model)
        }
        Window("Pair a device", id: "pair") {
            PairView(model: model)
        }
        .windowResizability(.contentSize)
    }
}

struct MenuContent: View {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(model.status)
        Divider()
        Button("Pair a device…") {
            openWindow(id: "pair")
            NSApp.activate()
        }
        .disabled(model.node == nil)
        Menu("Paired devices") {
            if model.peers.isEmpty { Text("None yet") }
            ForEach(model.peers, id: \.id) { peer in
                Menu(peer.name) {
                    Button("Send files to \(peer.name)…") { model.sendFiles(to: peer) }
                    Button("Forget") { model.forget(peer) }
                }
            }
            Divider()
            Button("Refresh") { model.refreshPeers() }
        }
        if !model.recent.isEmpty {
            Divider()
            ForEach(model.recent) { Text($0.text) }
        }
        Divider()
        Text("Core \(coreVersion())")
        Button("Quit Ferry") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }
}

struct PairView: View {
    let model: AppModel
    @State private var ticket: Result<String, Error>?

    var body: some View {
        VStack(spacing: 12) {
            switch ticket {
            case .success(let t):
                if let qr = qrImage(t) {
                    Image(nsImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 280, height: 280)
                        .accessibilityLabel("Pairing QR code")
                }
                Text("Scan with Ferry on your phone. Valid for 10 minutes.")
                    .font(.callout)
                Text(t)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                    .frame(width: 280)
            case .failure(let e):
                Text(verbatim: "Couldn't create a ticket: \(e)")
            case nil:
                ProgressView()
            }
            Button("New code") { ticket = model.newTicket() }
        }
        .padding(20)
        .onAppear { ticket = model.newTicket() }
        .onDisappear { model.refreshPeers() }
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage,
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        // 1 px per module; `.interpolation(.none)` scales it up without blurring.
        return NSImage(cgImage: cg, size: out.extent.size)
    }
}
