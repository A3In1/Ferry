import CoreGraphics
import Foundation
import FerryLogic
import Testing

private func bytes(_ b: [UInt8]) -> Data { Data(b) }

/// Phone → Mac stream: magic, INFO 1080x2400 caps 3, FRAME pts 0x0102 keyframe, END 2 "locked".
private let phoneStream: Data = Fsp.magic
    + Fsp.message(0x01, [0x04, 0x38, 0x09, 0x60, 0x03])
    + Fsp.message(0x02, [0, 0, 0, 0, 0, 0, 0x01, 0x02, 0x01, 0, 0, 0, 1, 0x65, 0xAA])
    + Fsp.message(0x03, [2] + Array("locked".utf8))

private let expected: [Fsp.Message] = [
    .info(width: 1080, height: 2400, caps: 3),
    .frame(pts: 0x0102, flags: 1, data: bytes([0, 0, 0, 1, 0x65, 0xAA])),
    .end(reason: 2, detail: "locked"),
]

@Test func fspRoundTrip() throws {
    var p = FspParser()
    let got = try p.push(phoneStream)
    #expect(got == expected)
    // Writer layout matches the spec byte for byte.
    #expect(Fsp.start(video: true) == bytes([0x20, 0, 0, 0, 7, 0x05, 0x00, 0x00, 0x7A, 0x12, 0x00, 1]))
    #expect(Fsp.touch(.move, 0.5, 1) == bytes([0x10, 0, 0, 0, 9, 2, 0x3F, 0, 0, 0, 0x3F, 0x80, 0, 0]))
    #expect(Fsp.key(67) == bytes([0x12, 0, 0, 0, 4, 0, 0, 0, 67]))
    #expect(Fsp.nav(.home) == bytes([0x14, 0, 0, 0, 1, 2]))
    #expect(Fsp.keyframe == bytes([0x15, 0, 0, 0, 0]))
    #expect(Fsp.cursor(.hide, 0, 0).prefix(6) == bytes([0x16, 0, 0, 0, 9, 3]))
    #expect(Fsp.text("hé") == bytes([0x13, 0, 0, 0, 3, 0x68, 0xC3, 0xA9]))
    #expect(Fsp.scroll(0, 0, dx: 0, dy: -1).suffix(4) == bytes([0xBF, 0x80, 0, 0]))
}

@Test func keyPresses() {
    #expect(Fsp.keyPress(keyCode: 51, characters: "\u{7F}") == Fsp.key(67))
    #expect(Fsp.keyPress(keyCode: 36, characters: "\r") == Fsp.key(66))
    #expect(Fsp.keyPress(keyCode: 0, characters: "a") == Fsp.text("a"))
    #expect(Fsp.keyPress(keyCode: 123, characters: "\u{F702}") == nil) // left arrow
    #expect(Fsp.keyPress(keyCode: 48, characters: "\t") == nil)
}

@Test func fspSplitChunks() throws {
    for size in [1, 2, 3, 7] {
        var p = FspParser(), got: [Fsp.Message] = []
        for i in stride(from: 0, to: phoneStream.count, by: size) {
            got += try p.push(phoneStream.subdata(in: i..<min(i + size, phoneStream.count)))
        }
        #expect(got == expected, "chunk size \(size)")
    }
}

@Test func fspSkipsUnknownTypes() throws {
    var p = FspParser()
    let data = Fsp.magic + Fsp.message(0x7F, [1, 2, 3]) + Fsp.message(0x01, [0, 10, 0, 20, 1]) + Fsp.message(0x42, [])
    let got = try p.push(data)
    #expect(got == [.info(width: 10, height: 20, caps: 1)])
}

@Test func fspRejectsOversizeAndBadMagic() {
    var p = FspParser()
    #expect(throws: Fsp.ProtocolError.self) { try p.push(Fsp.magic + bytes([0x02, 0x00, 0x80, 0x00, 0x01])) }
    var ok = FspParser() // exactly 8 MiB is allowed: it just waits for the payload
    let waiting = try? ok.push(Fsp.magic + bytes([0x02, 0x00, 0x80, 0x00, 0x00]))
    #expect(waiting == [])
    var bad = FspParser()
    #expect(throws: Fsp.ProtocolError.self) { try bad.push(Data("FSP2".utf8)) }
    var short = FspParser()
    #expect(throws: Fsp.ProtocolError.self) { try short.push(Fsp.magic + Fsp.message(0x01, [0, 1])) }
}

@Test func nalSplitting() {
    let sps = bytes([0x67, 0x42, 0x00, 0x1F]), pps = bytes([0x68, 0xCE, 0x3C, 0x80]), idr = bytes([0x65, 0x88, 0x00, 0x01, 0x84])
    // 4-byte, then 3-byte, then 4-byte start codes; the IDR contains 00 01 that isn't a start code.
    let stream = bytes([0, 0, 0, 1]) + sps + bytes([0, 0, 1]) + pps + bytes([0, 0, 0, 1]) + idr
    let nals = H264.nalUnits(stream)
    #expect(nals == [sps, pps, idr])
    #expect(nals.map(H264.type) == [7, 8, 5])
    #expect(H264.nalUnits(bytes([0, 0, 1]) + idr) == [idr])
    #expect(H264.nalUnits(Data()).isEmpty)
    #expect(H264.avcc([sps, pps]) == bytes([0, 0, 0, 4]) + sps + bytes([0, 0, 0, 4]) + pps)
}

@Test func edgePush() {
    #expect(pushesEdge(.right, x: 1439, dx: 3, screenMinX: 0, screenMaxX: 1440))
    #expect(!pushesEdge(.right, x: 1439, dx: -3, screenMinX: 0, screenMaxX: 1440))
    #expect(!pushesEdge(.right, x: 1200, dx: 3, screenMinX: 0, screenMaxX: 1440))
    #expect(pushesEdge(.left, x: -1920, dx: -1, screenMinX: -1920, screenMaxX: 0))
    #expect(!pushesEdge(.left, x: 0, dx: -1, screenMinX: -1920, screenMaxX: 0))
}

@Test func virtualCursor() {
    // Portrait 1080x2400 on a 1000 pt tall screen: 2.4 phone px per point.
    var c = VirtualCursor(width: 1080, height: 2400, edge: .right, macScreenHeight: 1000, entryY: 0.25)
    #expect(c.x == 0 && c.y == 600)
    var stays = c.move(dx: 100, dy: 0)
    #expect(stays && c.x == 240)
    stays = c.move(dx: 10_000, dy: 10_000)
    #expect(stays && c.x == 1080 && c.y == 2400) // clamped
    #expect(c.normalized == (1, 1))
    stays = c.move(dx: -450, dy: -1000)
    #expect(stays && c.x == 0 && c.y == 0)
    stays = c.move(dx: -1, dy: 0)
    #expect(!stays) // back past the left edge → Mac

    var l = VirtualCursor(width: 2400, height: 1080, edge: .left, macScreenHeight: 1200, entryY: 2)
    #expect(l.x == 2400 && l.y == 1080 && l.scale == 2)
    stays = l.move(dx: -10, dy: 0)
    #expect(stays && l.x == 2380)
    stays = l.move(dx: 11, dy: 0)
    #expect(!stays)
}

@Test func videoPoints() {
    // 1:2 video in a 200x200 view → drawn at x 50...150.
    let v = CGSize(width: 100, height: 200), b = CGRect(x: 0, y: 0, width: 200, height: 200)
    #expect(videoPoint(CGPoint(x: 50, y: 200), video: v, bounds: b, flipped: false) == CGPoint(x: 0, y: 0))
    #expect(videoPoint(CGPoint(x: 100, y: 50), video: v, bounds: b, flipped: false) == CGPoint(x: 0.5, y: 0.75))
    #expect(videoPoint(CGPoint(x: 100, y: 50), video: v, bounds: b, flipped: true) == CGPoint(x: 0.5, y: 0.25))
    #expect(videoPoint(CGPoint(x: 10, y: 50), video: v, bounds: b, flipped: false) == nil) // letterbox
    #expect(videoPoint(CGPoint(x: 10, y: 50), video: v, bounds: b, flipped: false, clamp: true) == CGPoint(x: 0, y: 0.75))
}
