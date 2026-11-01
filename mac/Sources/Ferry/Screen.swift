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

/// "<Phone> — Screen": the phone's screen in a window, with touch, scroll and typing back to it.
final class ScreenWindow: PeerWindow {
    private(set) var session: ScreenSession?
    private let screen = ScreenView()
    private let status = NSTextField(wrappingLabelWithString: "")
    private let spinner = NSProgressIndicator()
    private let reconnect = NSButton(title: "Reconnect", target: nil, action: nil)
    private let overlay = NSBox()
    private let peerName: String
    private var sps: Data?, pps: Data?
    private var format: CMVideoFormatDescription?
    private var needKeyframe = true
    private var lastKeyframeAsk = Date.distantPast

    init(model: AppModel, peer: FfiPeer) {
        peerName = peer.name
        super.init(model: model, peer: peer, kind: "Screen", size: NSSize(width: 360, height: 760),
                   minSize: NSSize(width: 160, height: 160), editing: false)
        let content = NSView()
        screen.autoresizingMask = [.width, .height]
        content.addSubview(screen)
        // Status card over the video: spinner while waiting, Reconnect once it ended.
        status.alignment = .center
        status.textColor = .white
        status.font = .systemFont(ofSize: NSFont.systemFontSize)
        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.appearance = NSAppearance(named: .darkAqua)
        reconnect.target = self
        reconnect.action = #selector(connect)
        let stack = NSStackView(views: [spinner, status, reconnect])
        stack.orientation = .vertical
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        overlay.boxType = .custom
        overlay.fillColor = NSColor.black.withAlphaComponent(0.7)
        overlay.borderWidth = 0
        overlay.cornerRadius = 12
        overlay.contentViewMargins = .zero
        overlay.contentView = stack
        overlay.appearance = NSAppearance(named: .darkAqua)
        overlay.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.centerXAnchor.constraint(equalTo: content.centerXAnchor),
            overlay.centerYAnchor.constraint(equalTo: content.centerYAnchor),
            overlay.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, constant: -32),
            overlay.widthAnchor.constraint(lessThanOrEqualToConstant: 300),
        ])
        window.contentView = content
        screen.frame = content.bounds

        // Nav buttons in the title bar; ScreenView maps ⌘[ ⌘H ⌘R to the same actions.
        let nav = NSStackView(views: [("chevron.backward", "Back", "⌘[", Fsp.Nav.back),
                                      ("circle", "Home", "⌘H", .home), ("square.on.square", "Recent Apps", "⌘R", .recents)]
            .map { symbol, name, key, n in
                let b = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: name)!,
                                 target: self, action: #selector(navButton))
                b.bezelStyle = .accessoryBarAction
                b.tag = Int(n.rawValue)
                b.toolTip = "\(name) (\(key))"
                b.setAccessibilityLabel(name)
                return b
            })
        let bar = NSTitlebarAccessoryViewController()
        bar.view = nav
        bar.layoutAttribute = .trailing
        window.addTitlebarAccessoryViewController(bar)

        screen.send = { [weak self] in self?.session?.send($0) }
        screen.nav = { [weak self] in self?.session?.send(Fsp.nav($0)) }
        connect()
        present()
        window.makeFirstResponder(screen)
    }

    @objc private func navButton(_ b: NSButton) {
        if let n = Fsp.Nav(rawValue: UInt8(b.tag)) { session?.send(Fsp.nav(n)) }
    }

    @objc private func connect() {
        session?.finish()
        guard let node = model.node else {
            return show(model.startFailed ? "Ferry couldn't start. Quit Ferry and open it again." : "Ferry is still starting. Try again in a moment.")
        }
        sps = nil
        pps = nil
        format = nil
        needKeyframe = true
        screen.display.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        show("Waiting for \(peerName)…\nOn the phone, tap Start now to share its screen.", reconnect: false)
        let s = ScreenSession(node: node, peerId: peerId, video: true)
        s.onMessage = { [weak self] in self?.handle($0) }
        session = s
    }

    /// `text` nil hides the status card (video is showing). Without Reconnect it's a wait, so it spins.
    private func show(_ text: String?, reconnect: Bool = true) {
        status.stringValue = text ?? ""
        overlay.isHidden = text == nil
        self.reconnect.isHidden = !reconnect
        self.reconnect.keyEquivalent = text != nil && reconnect ? "\r" : "" // else Return goes to the phone
        spinner.isHidden = reconnect
        if text != nil, !reconnect { spinner.startAnimation(nil) } else { spinner.stopAnimation(nil) }
        if let text { NSAccessibility.post(element: status, notification: .announcementRequested,
                                           userInfo: [.announcement: text, .priority: NSAccessibilityPriorityLevel.high.rawValue]) }
    }

    private func handle(_ m: Fsp.Message) {
        switch m {
        case let .info(w, h, caps):
            window.subtitle = caps & 2 != 0 ? "" : "To click and type, turn on Ferry control on the phone"
            let size = NSSize(width: w, height: h)
            guard w > 0, h > 0, screen.video != size else { return }
            screen.video = size
            window.contentAspectRatio = size
            let long = max(size.width, size.height)
            window.contentMinSize = NSSize(width: size.width * 240 / long, height: size.height * 240 / long)
            // Keep the window's long side (saved frame, or across a rotation), within 90% of the screen.
            let current = window.contentRect(forFrameRect: window.frame).size
            let visible = (window.screen ?? NSScreen.main)?.visibleFrame.size ?? size
            let s = min(max(current.width, current.height) / long, visible.width * 0.9 / size.width, visible.height * 0.9 / size.height)
            window.setContentSize(NSSize(width: (size.width * s).rounded(), height: (size.height * s).rounded()))
        case let .frame(pts, flags, data):
            decode(pts: pts, flags: flags, data)
        case let .end(reason, detail):
            if !detail.isEmpty { logger.notice("screen sharing ended (\(reason)): \(detail, privacy: .public)") }
            show(Fsp.endText(reason: reason, detail: detail))
        }
    }

    /// Annex-B → AVCC sample buffers into the display layer, which decodes them.
    private func decode(pts: UInt64, flags: UInt8, _ data: Data) {
        let nals = H264.nalUnits(data)
        var config = false // only a changed SPS/PPS needs a new format (keyframes usually repeat them)
        for n in nals {
            switch H264.type(n) {
            case H264.sps where n != sps: sps = n; config = true
            case H264.pps where n != pps: pps = n; config = true
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
        if !overlay.isHidden { show(nil) }
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
            logger.error("bad video frame: \(error, privacy: .public)")
            return nil
        }
    }

    override func windowWillClose(_ notification: Notification) {
        session?.finish()
        session = nil
        super.windowWillClose(notification)
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
        showWindow(\.viewers, for: peer) { ScreenWindow(model: self, peer: peer) }
    }
}
