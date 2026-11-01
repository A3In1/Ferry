import Foundation
import FerryLogic
import Testing

@Test func webURLs() {
    #expect(webURL("https://example.com/a?b=1")?.host == "example.com")
    #expect(webURL("  HTTP://example.com \n") != nil)
    for bad in ["file:///etc/passwd", "javascript:alert(1)", "ftp://x.com", "https://", "example.com",
                "https://a.com b", "ssh://host", ""] {
        #expect(webURL(bad) == nil, "\(bad)")
    }
}

@Test func categoryIDs() {
    let a = categoryID([("reply", "Reply", true), ("read", "Mark read", false)])
    #expect(a == categoryID([("reply", "Reply", true), ("read", "Mark read", false)]))
    #expect(a.hasPrefix("p|"))
    #expect(a != categoryID([("reply", "Reply", false), ("read", "Mark read", false)]))
    #expect(a != categoryID([("read", "Mark read", false), ("reply", "Reply", true)]))
    #expect(categoryID([("a|b", "c", false)]) != categoryID([("a", "b|c", false)]))
    #expect(categoryID([]) != a)
}

/// StreamPump over two bound stream pairs (stand-ins for an L2CAP channel): both directions, a
/// small buffer so writes hit backpressure, then EOF from the far side closes it.
@Test func streamPump() throws {
    func pair(_ size: Int) -> (InputStream, OutputStream) {
        var i: InputStream?, o: OutputStream?
        Stream.getBoundStreams(withBufferSize: size, inputStream: &i, outputStream: &o)
        return (i!, o!)
    }
    let (aIn, aOut) = pair(4096) // far side → pump
    let (bIn, bOut) = pair(4096) // pump → far side
    let got = Box(), echoed = Box(), closed = DispatchSemaphore(value: 0), readDone = DispatchSemaphore(value: 0)
    let pump = StreamPump(input: aIn, output: bOut, onRead: { got.append($0) }, onClose: { closed.signal() })
    let payload = Data((0..<300_000).map { UInt8(truncatingIfNeeded: $0 &* 7) })

    // pump → far side, read back on a plain blocking thread
    bIn.open()
    DispatchQueue.global().async {
        var buf = [UInt8](repeating: 0, count: 1000)
        while echoed.data.count < payload.count {
            let n = bIn.read(&buf, maxLength: buf.count)
            if n <= 0 { break }
            echoed.append(Data(buf[..<n]))
        }
        readDone.signal()
    }
    let wrote = stride(from: 0, to: payload.count, by: 16_384).allSatisfy {
        pump.write(payload[$0..<min($0 + 16_384, payload.count)])
    }
    #expect(wrote)
    #expect(readDone.wait(timeout: .now() + 5) == .success)
    #expect(echoed.data == payload)

    // far side → pump, then EOF
    aOut.open()
    let sent = payload.withUnsafeBytes { raw in
        var off = 0
        while off < raw.count {
            let n = aOut.write(raw.baseAddress!.advanced(by: off).assumingMemoryBound(to: UInt8.self),
                               maxLength: raw.count - off)
            if n <= 0 { return false }
            off += n
        }
        return true
    }
    #expect(sent)
    aOut.close()
    #expect(closed.wait(timeout: .now() + 5) == .success)
    #expect(got.data == payload)
    #expect(!pump.write(Data([1]))) // closed now
    pump.close() // idempotent
}

/// A closed pump is freed, even after more close() calls (each used to leave a block, holding the pump,
/// on the exited thread's run loop).
@Test func streamPumpFreedAfterClose() {
    func pair() -> (InputStream, OutputStream) {
        var i: InputStream?, o: OutputStream?
        Stream.getBoundStreams(withBufferSize: 1024, inputStream: &i, outputStream: &o)
        return (i!, o!)
    }
    let (aIn, _) = pair(), (_, bOut) = pair()
    let closed = DispatchSemaphore(value: 0)
    weak var weakPump: StreamPump?
    do {
        let pump = StreamPump(input: aIn, output: bOut, onRead: { _ in }, onClose: { closed.signal() })
        weakPump = pump
        pump.close()
        #expect(closed.wait(timeout: .now() + 5) == .success)
        pump.close()
        pump.close()
        #expect(!pump.write(Data([1])))
    }
    let deadline = Date.now + 5
    while weakPump != nil, Date.now < deadline { Thread.sleep(forTimeInterval: 0.01) }
    #expect(weakPump == nil)
}

final class Box: @unchecked Sendable {
    private let lock = NSLock()
    private var d = Data()
    func append(_ x: Data) { lock.withLock { d.append(x) } }
    var data: Data { lock.withLock { d } }
}
