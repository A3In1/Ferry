import AppKit
import FerryCore
import SwiftUI

/// Screen, Messages and Files: one window per phone, held in an AppModel table until it closes.
/// Titled "<Phone> — <kind>"; the frame is saved per phone and kind.
@MainActor class PeerWindow: NSObject, NSWindowDelegate {
    let window: NSWindow
    let model: AppModel
    let peerId: String
    private let autosaveName: String
    fileprivate var onClose: () -> Void = {}

    /// `editing`: an EditingWindow, for windows with text fields.
    init(model: AppModel, peer: FfiPeer, kind: String, size: NSSize, minSize: NSSize, editing: Bool = true) {
        self.model = model
        peerId = peer.id
        autosaveName = "\(kind) \(peer.id)"
        let rect = NSRect(origin: .zero, size: size), style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .resizable]
        window = editing ? EditingWindow(contentRect: rect, styleMask: style, backing: .buffered, defer: false)
            : NSWindow(contentRect: rect, styleMask: style, backing: .buffered, defer: false)
        super.init()
        window.title = "\(peer.name) — \(kind)"
        window.contentMinSize = minSize
        window.isReleasedWhenClosed = false
        window.delegate = self
    }

    /// The hosting controller resizes the window to the view's fitting size; this puts it back.
    func host(_ view: some View) {
        let size = window.contentRect(forFrameRect: window.frame).size
        window.contentViewController = NSHostingController(rootView: view)
        window.setContentSize(size)
    }

    /// Restores the saved frame (else centers), then shows the window.
    func present() {
        if !window.setFrameUsingName(autosaveName) { window.center() }
        window.setFrameAutosaveName(autosaveName)
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) { onClose() }
}

extension AppModel {
    /// Brings `peer`'s window in `table` to the front, or opens one with `make`.
    func showWindow<W: PeerWindow>(_ table: ReferenceWritableKeyPath<AppModel, [String: W]>, for peer: FfiPeer, _ make: () -> W) {
        if let w = self[keyPath: table][peer.id] {
            NSApp.activate()
            return w.window.makeKeyAndOrderFront(nil)
        }
        let w = make()
        w.onClose = { [weak self] in self?[keyPath: table][peer.id] = nil }
        self[keyPath: table][peer.id] = w
    }
}

/// No main menu in a menu-bar app, so text editing shortcuts and ⌘W are wired here.
final class EditingWindow: NSWindow {
    override func performKeyEquivalent(with e: NSEvent) -> Bool {
        guard e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command else { return super.performKeyEquivalent(with: e) }
        let action: Selector? = switch e.charactersIgnoringModifiers {
        case "x": #selector(NSText.cut(_:))
        case "c": #selector(NSText.copy(_:))
        case "v": #selector(NSText.paste(_:))
        case "a": #selector(NSText.selectAll(_:))
        case "z": Selector(("undo:"))
        case "w": #selector(NSWindow.performClose(_:))
        default: nil
        }
        if let action, NSApp.sendAction(action, to: nil, from: self) { return true }
        return super.performKeyEquivalent(with: e)
    }
}
