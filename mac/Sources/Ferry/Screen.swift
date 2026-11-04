import AVFoundation
import AppKit
import Synchronization
import VideoToolbox
import FerryCore
import FerryLogic

/// One FSP1 stream to a phone (docs/screen-protocol.md). Writes go out in order. The stream is read
/// off the main thread: FRAMEs go straight to `video` (if any), other messages arrive on the main actor,
/// and END (or EOF/error, as END 0/4) comes exactly once.
@MainActor final class ScreenSession {
    let peerId: String
    private(set) var info: (width: Int, height: Int, caps: UInt8)?
    private(set) var isOpen = true
    var onMessage: (Fsp.Message) -> Void = { _ in }
    /// Once, when the first picture is on screen.
    var onVideo: () -> Void = {}
    private let out: AsyncStream<Data>.Continuation
    private let video: VideoDecoder?

    /// `renderer` nil = control only (cursor crossing).
    init(node: FerryNode, peerId: String, renderer: AVSampleBufferVideoRenderer? = nil) {
        self.peerId = peerId
        let (queue, out) = AsyncStream.makeStream(of: Data.self)
        self.out = out
        video = renderer.map { VideoDecoder(renderer: $0, send: { out.yield($0) }) }
        out.yield(Fsp.magic + Fsp.start(video: renderer != nil))
        video?.onShown = { [weak self] in Task { @MainActor in self?.shown() } }
        Task { await run(node, queue) }
    }

    var canControl: Bool { (info?.caps ?? 0) & 2 != 0 }

    func send(_ data: Data) { if isOpen { out.yield(data) } }

    /// Ends our side; the phone then closes its side. No messages after this.
    func finish() {
        isOpen = false
        onMessage = { _ in }
        onVideo = {}
        video?.stop()
        out.finish()
    }

    private func shown() { if isOpen { onVideo() } }

    private func run(_ node: FerryNode, _ queue: AsyncStream<Data>) async {
        let stream: ScreenStream
        do { stream = try await node.openScreen(peerId: peerId) } catch { return end(4, "\(error)") }
        guard isOpen else { return await stream.finish() } // closed while connecting
        // Neither loop runs on the main actor, so a busy UI can't hold up the wire or the picture.
        Task.detached(priority: .userInitiated) {
            for await data in queue {
                do { try await stream.write(data: data) } catch { break }
            }
            await stream.finish()
        }
        let video = video, out = out
        let (reason, detail) = await Task.detached(priority: .userInitiated) { () async -> (UInt8, String) in
            var parser = FspParser()
            do {
                while true {
                    let chunk = try await stream.read()
                    if chunk.isEmpty { return (0, "") }
                    let at = monotonicMicros()
                    var newest: UInt64?
                    for m in try parser.push(chunk) {
                        switch m {
                        case let .frame(pts, flags, data):
                            video?.decode(pts: pts, flags: flags, data, at: at)
                            newest = pts
                        case let .stats(s): video?.phone(s)
                        case let .end(reason, detail): return (reason, detail)
                        default: await self.deliver(m)
                        }
                    }
                    // One ACK per chunk, for its newest frame: the phone times write → ACK and sees what's still on the way.
                    if let newest { out.yield(Fsp.ack(pts: newest)) }
                }
            } catch {
                return (4, "\(error)")
            }
        }.value
        end(reason, detail)
    }

    private func deliver(_ m: Fsp.Message) {
        guard isOpen else { return }
        if case let .info(w, h, caps) = m { info = (w, h, caps) }
        onMessage(m)
    }

    private func end(_ reason: UInt8, _ detail: String) {
        guard isOpen else { return }
        let handler = onMessage
        finish()
        handler(.end(reason: reason, detail: detail))
    }
}

/// Microseconds on a monotonic clock (stats and latency; not wall time).
func monotonicMicros() -> UInt64 { DispatchTime.now().uptimeNanoseconds / 1000 }

/// Annex-B FRAMEs → AVCC → VideoToolbox (hardware, real-time) → each decoded picture into the display
/// layer's renderer, marked to show at once: no timebase, no reorder wait (the phone sends no B-frames),
/// and we know when every picture is decoded and shown. On one background queue (the renderer may be fed
/// from any thread; nothing per frame touches the main thread). When frames pile up behind a slow decode,
/// the older ones are decoded but not shown. The format description is reused until SPS/PPS change. All
/// sessions share the queue, so a reconnect's flush lands after the old session's last frame.
final class VideoDecoder: @unchecked Sendable { // mutable state only on `queue`; onShown is set before the first frame
    private static let queue = DispatchQueue(label: "ferry.screen.video", qos: .userInteractive)
    private let renderer: AVSampleBufferVideoRenderer
    private let send: @Sendable (Data) -> Void
    var onShown: @Sendable () -> Void = {}
    private var sps: Data?, pps: Data?
    private var format: CMVideoFormatDescription?
    private var session: VTDecompressionSession?
    private var pictureFormat: CMVideoFormatDescription?
    private var nals: [Range<Int>] = [], slices: [Range<Int>] = []
    private var needKeyframe = true, shown = false, stopped = false
    private var busy = 0 // frames in a row the layer wasn't ready for
    private var lastKeyframeAsk = DispatchTime(uptimeNanoseconds: 0)
    private var stats = ScreenStats(now: monotonicMicros())
    /// FRAMEs handed over and not yet decoded.
    private let waiting = Atomic<Int>(0)

    /// `send` writes a Mac → phone message (KEYFRAME requests).
    init(renderer: AVSampleBufferVideoRenderer, send: @escaping @Sendable (Data) -> Void) {
        self.renderer = renderer
        self.send = send
        Self.queue.async { [self] in self.renderer.flush(removingDisplayedImage: true, completionHandler: nil) }
    }

    /// `at`: when the FRAME arrived (monotonicMicros).
    func decode(pts: UInt64, flags: UInt8, _ data: Data, at: UInt64) {
        waiting.add(1, ordering: .relaxed)
        Self.queue.async { [self] in
            let newer = waiting.subtract(1, ordering: .relaxed).newValue
            guard !stopped else { return }
            stats.received(pts: pts, bytes: data.count, config: flags & 2 != 0, at: at)
            process(pts, flags, data, at: at, show: newer == 0)
            if let line = stats.report(at: monotonicMicros()) { logger.notice("\(line, privacy: .public)") }
        }
    }

    /// The phone's STATS, logged with the next window.
    func phone(_ s: Fsp.PhoneStats) { Self.queue.async { [self] in stats.phone(s) } }

    /// No more frames from this session go to the renderer.
    func stop() {
        Self.queue.async { [self] in
            stopped = true
            reset()
        }
    }

    private func reset() {
        if let session { VTDecompressionSessionInvalidate(session) }
        session = nil
    }

    /// `show` false: newer frames are already waiting, so decode this one (they need it) but don't display it.
    private func process(_ pts: UInt64, _ flags: UInt8, _ data: Data, at: UInt64, show: Bool) {
        let sample: CMSampleBuffer? = data.withUnsafeBytes { raw in
            nals.removeAll(keepingCapacity: true)
            slices.removeAll(keepingCapacity: true)
            H264.nalRanges(raw, into: &nals)
            var config = false // only a changed SPS/PPS needs a new format (keyframes usually repeat them)
            var key = flags & 1 != 0
            for r in nals {
                switch raw[r.lowerBound] & 0x1F {
                case H264.sps where sps.map { !raw[r].elementsEqual($0) } ?? true: sps = Data(raw[r]); config = true
                case H264.pps where pps.map { !raw[r].elementsEqual($0) } ?? true: pps = Data(raw[r]); config = true
                case 5: slices.append(r); key = true
                case 1...4: slices.append(r)
                default: break
                }
            }
            if config, let sps, let pps {
                format = try? CMVideoFormatDescription(h264ParameterSets: [sps, pps], nalUnitHeaderLength: 4)
                if let session, let format, !VTDecompressionSessionCanAcceptFormatDescription(session, formatDescription: format) { reset() }
            }
            guard !slices.isEmpty else { return nil }
            guard let format, key || !needKeyframe, let sample = sampleBuffer(raw, pts: pts, key: key, format: format) else {
                stats.dropped()
                askKeyframe()
                return nil
            }
            return sample
        }
        guard let sample, let format else { return }
        if session == nil { session = makeSession(format) }
        guard let session else { return askKeyframe() }
        let start = monotonicMicros()
        let out = Decoded()
        // Synchronous: the handler runs before DecodeFrame returns; WaitForAsynchronousFrames makes sure of it.
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [._1xRealTimePlayback], infoFlagsOut: nil) {
            status, _, image, _, _ in
            out.status = status
            out.image = image
        }
        VTDecompressionSessionWaitForAsynchronousFrames(session)
        guard status == noErr, out.status == noErr, let image = out.image else {
            // Bad data, or the session died (sleep, GPU change): start over from a keyframe.
            logger.notice("screen: decode failed (\(status), \(out.status)); asking for a keyframe")
            reset()
            stats.dropped()
            askKeyframe()
            return
        }
        needKeyframe = false
        stats.decoded(us: monotonicMicros() - start)
        // Behind: newer frames waiting, or the layer still busy with the last picture. Show only the newest.
        guard show else { return stats.skippedBehind() }
        if !renderer.isReadyForMoreMediaData {
            busy += 1
            guard busy >= 10 else { return stats.skippedBehind() }
            renderer.flush() // stuck for 10 frames: start it over rather than freeze
        }
        busy = 0
        if renderer.status == .failed {
            logger.notice("screen: display layer failed (\(String(describing: self.renderer.error), privacy: .public)); flushing")
            renderer.flush()
        }
        guard let picture = picture(image, pts: pts) else { return }
        renderer.enqueue(picture)
        stats.shown(after: monotonicMicros() - at)
        if !shown {
            shown = true
            onShown()
        }
    }

    private final class Decoded: @unchecked Sendable { // written by the synchronous decode callback, read right after
        var status: OSStatus = noErr
        var image: CVImageBuffer?
    }

    private func makeSession(_ format: CMVideoFormatDescription) -> VTDecompressionSession? {
        let spec = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true] as CFDictionary
        let pixels = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                      kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                      kCVPixelBufferMetalCompatibilityKey: true] as CFDictionary
        var s: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: spec,
                                                  imageBufferAttributes: pixels, outputCallback: nil, decompressionSessionOut: &s)
        guard status == noErr, let s else {
            logger.error("screen: no video decoder (\(status))")
            return nil
        }
        VTSessionSetProperty(s, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        return s
    }

    /// A decoded picture as a sample the layer shows at once.
    private func picture(_ image: CVImageBuffer, pts: UInt64) -> CMSampleBuffer? {
        if pictureFormat.map({ !CMVideoFormatDescriptionMatchesImageBuffer($0, imageBuffer: image) }) ?? true {
            pictureFormat = try? CMVideoFormatDescription(imageBuffer: image)
        }
        guard let pictureFormat else { return nil }
        let timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: CMTimeValue(pts), timescale: 1_000_000),
                                        decodeTimeStamp: .invalid)
        guard let sample = try? CMSampleBuffer(imageBuffer: image, formatDescription: pictureFormat, sampleTiming: timing) else { return nil }
        sample.sampleAttachments[0][.displayImmediately] = true
        return sample
    }

    private func askKeyframe() {
        needKeyframe = true
        guard DispatchTime.now().uptimeNanoseconds > lastKeyframeAsk.uptimeNanoseconds + 1_000_000_000 else { return }
        lastKeyframeAsk = .now()
        send(Fsp.keyframe)
    }

    /// One compressed sample in CoreMedia-owned memory, filled straight from the FRAME payload.
    private func sampleBuffer(_ raw: UnsafeRawBufferPointer, pts: UInt64, key: Bool, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        let size = H264.avccSize(slices)
        var block: CMBlockBuffer?
        var dst: UnsafeMutablePointer<CChar>?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: size,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: size, flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr,
            let block, CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: nil, dataPointerOut: &dst) == noErr,
            let dst
        else { return nil }
        H264.writeAVCC(raw, slices, to: UnsafeMutableRawBufferPointer(start: dst, count: size))
        do {
            let timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMTime(value: CMTimeValue(pts), timescale: 1_000_000),
                                            decodeTimeStamp: .invalid)
            let sample = try CMSampleBuffer(dataBuffer: block, formatDescription: format, numSamples: 1,
                                            sampleTimings: [timing], sampleSizes: [size])
            if !key { sample.sampleAttachments[0][.notSync] = true }
            return sample
        } catch {
            logger.error("bad video frame: \(error, privacy: .public)")
            return nil
        }
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
        nav.spacing = 2
        nav.frame = NSRect(origin: .zero, size: nav.fittingSize) // a zero frame's autoresizing constraints fight the stack's
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
        show("Waiting for \(peerName)…\nOn the phone, tap Start now to share its screen.", reconnect: false)
        let s = ScreenSession(node: node, peerId: peerId, renderer: screen.display.sampleBufferRenderer)
        s.onMessage = { [weak self] in self?.handle($0) }
        s.onVideo = { [weak self] in self?.show(nil) }
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
        case .frame, .stats:
            break // the session hands these to its VideoDecoder
        case let .quality(bitrate):
            logger.debug("phone encoder at \(bitrate / 1000) kbps")
        case let .end(reason, detail):
            if !detail.isEmpty { logger.notice("screen sharing ended (\(reason)): \(detail, privacy: .public)") }
            show(Fsp.endText(reason: reason, detail: detail))
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
        // Layer-hosting: AppKit doesn't turn off implicit animations, so a live resize would ease every frame.
        display.actions = ["bounds": NSNull(), "position": NSNull(), "frame": NSNull(), "contents": NSNull()]
        layer = display // layer-hosting: the overlay lives in a sibling view
        wantsLayer = true
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        display.contentsScale = window?.backingScaleFactor ?? 2
    }

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
        // Only a new scroll must start over the video; the rest of one (and its end) still goes if the pointer drifts off.
        guard let w = Wheel(e), let p = point(e, clamp: !w.starts) else { return }
        send(Fsp.scroll(Float(p.x), Float(p.y), dx: w.dx, dy: w.dy, flags: w.flags))
    }

    override func keyDown(with e: NSEvent) {
        if let m = keyMessage(e) { send(m) } else { super.keyDown(with: e) }
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

/// A scroll event as SCROLL: deltas in wheel lines (+dy = scroll up; trackpads report points, about 10
/// per line) as macOS delivers them, so the user's natural-scrolling setting carries over. Trackpad
/// deltas and momentum are continuous; the phone plays them as one finger drag and lifts it on end.
struct Wheel {
    let dx, dy: Float
    let flags: UInt8
    /// The first event of a scroll (or any wheel step).
    let starts: Bool

    /// nil for events with nothing to send (a trackpad touch that hasn't moved yet).
    init?(_ e: NSEvent) {
        let precise = e.hasPreciseScrollingDeltas, k: CGFloat = precise ? 0.1 : 1
        dx = Float(e.scrollingDeltaX * k)
        dy = Float(e.scrollingDeltaY * k)
        let over: NSEvent.Phase = [.ended, .cancelled]
        // A finger lift may be followed by momentum; the phone waits a moment before lifting on a phase end.
        let end = !e.momentumPhase.isDisjoint(with: over) || (e.momentumPhase.isEmpty && !e.phase.isDisjoint(with: over))
        flags = (precise ? Fsp.scrollContinuous : 0) | (end ? Fsp.scrollEnd : 0)
        starts = !precise || e.phase.contains(.began) || e.phase.contains(.mayBegin)
        if dx == 0, dy == 0, !end { return nil }
    }
}

/// KEY/TEXT for a key press; ⌃ combinations and ⌘ ones other than ⌘-arrows send nothing.
func keyMessage(_ e: NSEvent) -> Data? {
    let m = e.modifierFlags
    guard !m.contains(.control) else { return nil }
    return Fsp.keyPress(keyCode: e.keyCode, characters: e.characters, shift: m.contains(.shift), option: m.contains(.option),
                        command: m.contains(.command))
}

extension AppModel {
    func mirror(_ peer: FfiPeer) {
        showWindow(\.viewers, for: peer) { ScreenWindow(model: self, peer: peer) }
    }
}
