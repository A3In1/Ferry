import Foundation

/// Runs a duplex Foundation stream pair (a `CBL2CAPChannel`'s) on its own thread and run loop.
/// Every stream call happens on that thread. `write` blocks its caller until the bytes are written,
/// which is what the core's `ByteSink` wants (it calls from a blocking worker, one write at a time).
/// `onRead` and `onClose` run on the pump thread; `onClose` runs once, after both streams are closed.
public final class StreamPump: NSObject, StreamDelegate, @unchecked Sendable {
    private let input: InputStream
    private let output: OutputStream
    private let onRead: @Sendable (Data) -> Void
    private let onClose: @Sendable () -> Void
    private var runLoop: CFRunLoop!
    private var pending = Data() // pump thread only
    private var inFlight = false // pump thread only: a writer is waiting on `pending`

    // Shared with writers; guarded by `lock`.
    private let lock = NSLock()
    private var closed = false
    private var waiter: DispatchSemaphore?
    private var writeOK = false

    public init(input: InputStream, output: OutputStream,
                onRead: @escaping @Sendable (Data) -> Void, onClose: @escaping @Sendable () -> Void) {
        self.input = input
        self.output = output
        self.onRead = onRead
        self.onClose = onClose
        super.init()
        let ready = DispatchSemaphore(value: 0)
        // The thread keeps `self` alive until the streams close.
        let thread = Thread { [self] in
            runLoop = CFRunLoopGetCurrent()
            for s in [self.input, self.output] as [Stream] {
                s.delegate = self
                s.schedule(in: .current, forMode: .default)
                s.open()
            }
            ready.signal()
            while !isClosed { _ = RunLoop.current.run(mode: .default, before: .distantFuture) }
        }
        thread.name = "ferry.stream-pump"
        thread.qualityOfService = .userInitiated
        thread.start()
        ready.wait()
    }

    private var isClosed: Bool { lock.withLock { closed } }

    /// Writes all of `data`; false once the streams are closed or failed. Don't call on the pump thread.
    public func write(_ data: Data) -> Bool {
        let done = DispatchSemaphore(value: 0)
        let ok: Bool = lock.withLock {
            guard !closed else { return false }
            waiter = done
            writeOK = false
            return true
        }
        guard ok else { return false }
        perform { [self] in
            guard !isClosed else { return }
            pending.append(data)
            inFlight = true
            flush()
        }
        done.wait() // signalled by flush() or shutdown(), whichever comes first
        return lock.withLock { writeOK }
    }

    /// Closes both streams (from any thread); `onClose` follows. Safe to call more than once.
    public func close() {
        perform { [self] in shutdown() }
    }

    public func stream(_ s: Stream, handle event: Stream.Event) {
        switch event {
        case .hasBytesAvailable: readAll()
        case .hasSpaceAvailable: flush()
        case .errorOccurred, .endEncountered: shutdown()
        default: break
        }
    }

    // MARK: pump thread

    private func perform(_ block: @escaping () -> Void) {
        CFRunLoopPerformBlock(runLoop, CFRunLoopMode.defaultMode.rawValue, block)
        CFRunLoopWakeUp(runLoop)
    }

    private func readAll() {
        var buf = [UInt8](repeating: 0, count: 16 * 1024)
        while input.hasBytesAvailable {
            let n = input.read(&buf, maxLength: buf.count)
            if n < 0 { return shutdown() }
            if n == 0 { return } // .endEncountered follows
            onRead(Data(buf[..<n]))
        }
    }

    private func flush() {
        while !pending.isEmpty, output.hasSpaceAvailable {
            let n = pending.withUnsafeBytes { output.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: $0.count) }
            if n < 0 { return shutdown() }
            if n == 0 { break }
            pending.removeFirst(n)
        }
        if pending.isEmpty, inFlight { // else: wait for .hasSpaceAvailable
            inFlight = false
            finishWrite(true)
        }
    }

    private func finishWrite(_ ok: Bool) {
        lock.withLock {
            writeOK = ok
            waiter?.signal()
            waiter = nil
        }
    }

    private func shutdown() {
        let first: Bool = lock.withLock {
            defer { closed = true }
            return !closed
        }
        guard first else { return }
        finishWrite(false)
        pending = Data()
        for s in [input, output] as [Stream] {
            s.delegate = nil
            s.remove(from: .current, forMode: .default)
            s.close()
        }
        CFRunLoopStop(runLoop)
        onClose()
    }
}
