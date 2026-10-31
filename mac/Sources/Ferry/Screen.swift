import AVFoundation
import AppKit
import FerryCore
import FerryLogic

/// One FSP1 stream to a phone (docs/screen-protocol.md). Writes go out in order; messages arrive on
/// the main actor, and END (or EOF/error, as END 0/4) comes exactly once.
@MainActor final class ScreenSession {
    let peerId: String
    private(set) var info: (width: Int, height: Int, caps: UInt8)?
    private(set) var isOpen = true
    var onMessage: (Fsp.Message) -> Void = { _ in }
    private let out: AsyncStream<Data>.Continuation

    init(node: FerryNode, peerId: String, video: Bool) {
        self.peerId = peerId
        let (queue, out) = AsyncStream.makeStream(of: Data.self)
        self.out = out
        out.yield(Fsp.magic + Fsp.start(video: video))
        Task { await run(node, queue) }
    }

    var canControl: Bool { (info?.caps ?? 0) & 2 != 0 }

    func send(_ data: Data) { if isOpen { out.yield(data) } }

    /// Ends our side; the phone then closes its side. No messages after this.
    func finish() {
        isOpen = false
        onMessage = { _ in }
        out.finish()
    }

    private func run(_ node: FerryNode, _ queue: AsyncStream<Data>) async {
        let stream: ScreenStream
        do { stream = try await node.openScreen(peerId: peerId) } catch { return end(4, "\(error)") }
        guard isOpen else { return await stream.finish() } // closed while connecting
        Task {
            for await data in queue {
                do { try await stream.write(data: data) } catch { break }
            }
            await stream.finish()
        }
        var parser = FspParser()
        do {
            while true {
                let chunk = try await stream.read()
                if chunk.isEmpty { break }
                for m in try parser.push(chunk) where isOpen {
                    if case let .end(reason, detail) = m { end(reason, detail); continue }
                    if case let .info(w, h, caps) = m { info = (w, h, caps) }
                    onMessage(m)
                }
            }
            end(0, "")
        } catch {
            end(4, "\(error)")
        }
    }

    private func end(_ reason: UInt8, _ detail: String) {
        guard isOpen else { return }
        let handler = onMessage
        finish()
        handler(.end(reason: reason, detail: detail))
    }
}

/// "Mirror <phone>": the phone's screen in a window, with touch, scroll and typing back to it.
@MainActor final class ScreenWindow: NSObject, NSWindowDelegate {
    let peerId: String
    let window: NSWindow
    private(set) var session: ScreenSession?
    private let model: AppModel
    private let screen = ScreenView()
    private let status = NSTextField(wrappingLabelWithString: "")
    private let reconnect = NSButton(title: "Reconnect", target: nil, action: nil)
    private var sps: Data?, pps: Data?
    private var format: CMVideoFormatDescription?
    private var needKeyframe = true
    private var lastKeyframeAsk = Date.distantPast

    init(model: AppModel, peer: FfiPeer) {
        self.model = model
        peerId = peer.id
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 760),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        super.init()
        window.title = peer.name
        window.isReleasedWhenClosed = false
        window.delegate = self

        let content = NSView()
        screen.autoresizingMask = [.width, .height]
        content.addSubview(screen)
        status.alignment = .center
        status.textColor = .white
        reconnect.target = self
        reconnect.action = #selector(connect)
        let overlay = NSStackView(views: [status, reconnect])
        overlay.orientation = .vertical
        overlay.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            overlay.centerYAnchor.constraint(equalTo: content.centerYAnchor),
            overlay.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, constant: -32),
        ])
        window.contentView = content
        screen.frame = content.bounds

        // Nav buttons in the title bar; ScreenView maps ⌘[ ⌘H ⌘R to the same actions.
        let nav = NSStackView(views: [("chevron.backward", "Back (⌘[)", Fsp.Nav.back),
                                      ("circle", "Home (⌘H)", .home), ("square.on.square", "Recents (⌘R)", .recents)]
            .map { symbol, tip, n in
                let b = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: tip)!,
                                 target: self, action: #selector(navButton))
                b.bezelStyle = .accessoryBarAction
                b.tag = Int(n.rawValue)
                b.toolTip = tip
                return b
            })
        let bar = NSTitlebarAccessoryViewController()
        bar.view = nav
        bar.layoutAttribute = .trailing
        window.addTitlebarAccessoryViewController(bar)

        screen.send = { [weak self] in self?.session?.send($0) }
        screen.nav = { [weak self] in self?.session?.send(Fsp.nav($0)) }
        connect()
        window.center()
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(screen)
    }

    @objc private func navButton(_ b: NSButton) {
        if let n = Fsp.Nav(rawValue: UInt8(b.tag)) { session?.send(Fsp.nav(n)) }
    }

    @objc private func connect() {
        session?.finish()
        guard let node = model.node else { return show("Ferry isn't running") }
        sps = nil
        pps = nil
        format = nil
        needKeyframe = true
        screen.display.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        show("Tap “Start now” on the phone to share its screen", reconnect: false)
        let s = ScreenSession(node: node, peerId: peerId, video: true)
        s.onMessage = { [weak self] in self?.handle($0) }
        session = s
    }

    private func show(_ text: String?, reconnect: Bool = true) {
        status.stringValue = text ?? ""
        status.superview?.isHidden = text == nil
        self.reconnect.isHidden = !reconnect
    }

    private func handle(_ m: Fsp.Message) {
        switch m {
        case let .info(w, h, caps):
            window.subtitle = caps & 2 != 0 ? "" : "Turn on Ferry control on the phone to click and type"
            let size = NSSize(width: w, height: h)
            guard w > 0, h > 0, screen.video != size else { return }
            screen.video = size
            window.contentAspectRatio = size
            let visible = (window.screen ?? NSScreen.main)?.visibleFrame.size ?? size
            let s = min(visible.width * 0.8 / size.width, visible.height * 0.8 / size.height, 1)
            window.setContentSize(NSSize(width: size.width * s, height: size.height * s))
        case let .frame(pts, flags, data):
            decode(pts: pts, flags: flags, data)
        case let .end(reason, detail):
            show(Fsp.endText(reason: reason, detail: detail))
        }
    }

    /// Annex-B → AVCC sample buffers into the display layer, which decodes them.
    private func decode(pts: UInt64, flags: UInt8, _ data: Data) {
        let nals = H264.nalUnits(data)
        var config = false
        for n in nals {
            switch H264.type(n) {
            case H264.sps: sps = n; config = true
            case H264.pps: pps = n; config = true
            default: break
            }
        }
        if config, let sps, let pps {
            format = try? CMVideoFormatDescription(h264ParameterSets: [sps, pps], nalUnitHeaderLength: 4)
        }
        let slices = nals.filter { (1...5).contains(H264.type($0)) }
        guard !slices.isEmpty else { return }
        let key = flags & 1 != 0 || slices.contains { H264.type($0) == 5 }
        let renderer = screen.display.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            renderer.flush()
            needKeyframe = true
        }
        guard let format, key || !needKeyframe, let sample = sampleBuffer(H264.avcc(slices), pts: pts, key: key, format: format)
        else { return askKeyframe() }
        needKeyframe = false
        renderer.enqueue(sample)
        if !status.superview!.isHidden { show(nil) }
    }

    private func askKeyframe() {
        needKeyframe = true
        guard Date.now.timeIntervalSince(lastKeyframeAsk) > 1 else { return }
        lastKeyframeAsk = .now
        session?.send(Fsp.keyframe)
    }

    private func sampleBuffer(_ avcc: Data, pts: UInt64, key: Bool, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        do {
            let block = try CMBlockBuffer(length: avcc.count)
            try avcc.withUnsafeBytes { try block.replaceDataBytes(with: $0) }
            let timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: CMTimeValue(pts), timescale: 1_000_000),
                                            decodeTimeStamp: .invalid)
            let sample = try CMSampleBuffer(dataBuffer: block, formatDescription: format, numSamples: 1,
                                            sampleTimings: [timing], sampleSizes: [avcc.count])
            sample.sampleAttachments[0][.displayImmediately] = true
            if !key { sample.sampleAttachments[0][.notSync] = true }
            return sample
        } catch {
            NSLog("Ferry: bad video frame: \(error)")
            return nil
        }
    }

    func windowWillClose(_ notification: Notification) {
        session?.finish()
        session = nil
        model.viewers[peerId] = nil
    }
}

/// The video plus mouse/keyboard input, sent as normalized TOUCH/SCROLL and TEXT/KEY.
final class ScreenView: NSView {
    let display = AVSampleBufferDisplayLayer()
    var video = CGSize.zero
    var send: (Data) -> Void = { _ in }
    var nav: (Fsp.Nav) -> Void = { _ in }
    private var touching = false

    override init(frame: NSRect) {
        super.init(frame: frame)
        display.videoGravity = .resizeAspect
        display.backgroundColor = NSColor.black.cgColor
        layer = display // layer-hosting: the overlay lives in a sibling view
        wantsLayer = true
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    private func point(_ e: NSEvent, clamp: Bool = false) -> CGPoint? {
        videoPoint(convert(e.locationInWindow, from: nil), video: video, bounds: bounds, flipped: isFlipped, clamp: clamp)
    }

    override func mouseDown(with e: NSEvent) {
        guard let p = point(e) else { return }
        touching = true
        send(Fsp.touch(.down, Float(p.x), Float(p.y)))
    }

    override func mouseDragged(with e: NSEvent) {
        guard touching, let p = point(e, clamp: true) else { return }
        send(Fsp.touch(.move, Float(p.x), Float(p.y)))
    }

    override func mouseUp(with e: NSEvent) {
        guard touching, let p = point(e, clamp: true) else { return }
        touching = false
        send(Fsp.touch(.up, Float(p.x), Float(p.y)))
    }

    override func scrollWheel(with e: NSEvent) {
        guard let p = point(e) else { return }
        let (dx, dy) = wheelUnits(e)
        send(Fsp.scroll(Float(p.x), Float(p.y), dx: dx, dy: dy))
    }

    override func keyDown(with e: NSEvent) {
        if let m = Fsp.keyPress(keyCode: e.keyCode, characters: e.characters) { send(m) } else { super.keyDown(with: e) }
    }

    /// No main menu in a menu-bar app, so the window's shortcuts live here.
    override func performKeyEquivalent(with e: NSEvent) -> Bool {
        guard e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command else { return super.performKeyEquivalent(with: e) }
        switch e.charactersIgnoringModifiers {
        case "[": nav(.back)
        case "h": nav(.home)
        case "r": nav(.recents)
        case "w": window?.performClose(nil)
        default: return super.performKeyEquivalent(with: e)
        }
        return true
    }
}

/// Scroll deltas in wheel lines (+dy = scroll up). Trackpads report points, about 10 per line.
func wheelUnits(_ e: NSEvent) -> (Float, Float) {
    let k: CGFloat = e.hasPreciseScrollingDeltas ? 0.1 : 1
    return (Float(e.scrollingDeltaX * k), Float(e.scrollingDeltaY * k))
}

extension AppModel {
    func mirror(_ peer: FfiPeer) {
        if let w = viewers[peer.id] {
            NSApp.activate()
            return w.window.makeKeyAndOrderFront(nil)
        }
        viewers[peer.id] = ScreenWindow(model: self, peer: peer)
    }
}
