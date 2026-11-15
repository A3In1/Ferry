import Foundation
import FerryLogic
import Testing

private func bytes(_ b: [UInt8]) -> Data { Data(b) }

/// Phone → Mac stream: magic, INFO 1080x2400 caps 2, END 2 "locked".
private let phoneStream: Data = Fsp.magic
    + Fsp.message(0x01, [0x04, 0x38, 0x09, 0x60, 0x02])
    + Fsp.message(0x03, [2] + Array("locked".utf8))

private let expected: [Fsp.Message] = [
    .info(width: 1080, height: 2400, caps: 2),
    .end(reason: 2, detail: "locked"),
]

@Test func fspRoundTrip() throws {
    var p = FspParser()
    let got = try p.push(phoneStream)
    #expect(got == expected)
    // Writer layout matches the spec byte for byte.
    #expect(Fsp.start == bytes([0x20, 0, 0, 0, 0]))
    #expect(Fsp.cursor(.move, 0.5, 1) == bytes([0x16, 0, 0, 0, 9, 2, 0x3F, 0, 0, 0, 0x3F, 0x80, 0, 0]))
    #expect(Fsp.cursor(.hide, 0, 0).prefix(6) == bytes([0x16, 0, 0, 0, 9, 3]))
    #expect(Fsp.text("hé") == bytes([0x13, 0, 0, 0, 3, 0x68, 0xC3, 0xA9]))
    #expect(Fsp.scroll(0, 0, dx: 0, dy: -1).suffix(5) == bytes([0xBF, 0x80, 0, 0, 0]))
    #expect(Fsp.scroll(0, 0, dx: 0, dy: 0, flags: Fsp.scrollContinuous | Fsp.scrollEnd).prefix(5) == bytes([0x11, 0, 0, 0, 17]))
    #expect(Fsp.scroll(0, 0, dx: 0, dy: 0, flags: 3).last == 3)
}

@Test func keyPresses() {
    #expect(Fsp.keyPress(keyCode: 51, characters: "\u{7F}") == Fsp.key(67))
    #expect(Fsp.keyPress(keyCode: 51, characters: "\u{7F}", option: true) == Fsp.key(67, meta: Fsp.keyOption)) // delete word
    #expect(Fsp.keyPress(keyCode: 36, characters: "\r") == Fsp.key(66))
    #expect(Fsp.keyPress(keyCode: 0, characters: "a") == Fsp.text("a"))
    #expect(Fsp.keyPress(keyCode: 0, characters: "a", command: true) == nil)
    #expect(Fsp.keyPress(keyCode: 123, characters: "\u{F702}") == Fsp.key(21)) // left arrow
    #expect(Fsp.keyPress(keyCode: 124, characters: "\u{F703}", shift: true) == Fsp.key(22, meta: Fsp.keyShift))
    #expect(Fsp.keyPress(keyCode: 123, characters: "\u{F702}", shift: true, option: true, command: true) == Fsp.key(122, meta: Fsp.keyShift))
    #expect(Fsp.keyPress(keyCode: 117, characters: "\u{F728}") == Fsp.key(112))
    #expect(Fsp.keyPress(keyCode: 48, characters: "\t") == nil)
    #expect(Fsp.key(67) == bytes([0x12, 0, 0, 0, 5, 0, 0, 0, 67, 0]))
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
    let data = Fsp.magic + Fsp.message(0x7F, [1, 2, 3]) + Fsp.message(0x01, [0, 10, 0, 20, 2, 9]) // a byte from a future version
        + Fsp.message(0x42, []) + Fsp.message(0x03, [0])
    let got = try p.push(data)
    #expect(got == [.info(width: 10, height: 20, caps: 2), .end(reason: 0, detail: "")])
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
    stays = c.move(dx: 0.25, dy: 0.25) // sub-pixel moves add up
    stays = c.move(dx: 0.25, dy: 0.25)
    #expect(stays && c.x == 241.2 && c.y == 601.2)
    stays = c.move(dx: 10_000, dy: 10_000)
    #expect(stays && c.x == 1080 && c.y == 2400) // clamped
    #expect(c.normalized == (1, 1))
    stays = c.move(dx: -450, dy: -1000)
    #expect(stays && c.x == 0 && c.y == 0)
    stays = c.move(dx: -1, dy: 0)
    #expect(stays && c.normalized.x == 0) // inside the dead zone: still on the phone, drawn at its edge
    stays = c.move(dx: 1, dy: 0)
    #expect(stays && c.x == 0)
    stays = c.move(dx: -VirtualCursor.exitPush, dy: 0)
    #expect(!stays) // back past the left edge → Mac

    var l = VirtualCursor(width: 2400, height: 1080, edge: .left, macScreenHeight: 1200, entryY: 2)
    #expect(l.x == 2400 && l.y == 1080 && l.scale == 2)
    stays = l.move(dx: -10, dy: 0)
    #expect(stays && l.x == 2380)
    stays = l.move(dx: 11, dy: 0)
    #expect(stays && l.x == 2402)
    stays = l.move(dx: 3, dy: 0)
    #expect(!stays)
}

@Test func edgeDeadZoneAndPacing() {
    var p = EdgePush(distance: 8, window: 0.4)
    let pushes = [p.push(3, at: 0), p.push(3, at: 0.1), p.push(-3, at: 0.2)] // 9 pt of push → cross
    #expect(pushes == [false, false, true])
    let paused = [p.push(5, at: 1), p.push(5, at: 1.5)] // a pause starts over
    #expect(paused == [false, false])
    p.reset()
    let after = p.push(7, at: 1.6)
    #expect(!after)

    var pacer = Pacer(interval: 0.01)
    #expect(pacer.wait(now: 5) == 0)
    pacer.sent(at: 5)
    #expect(abs(pacer.wait(now: 5.004) - 0.006) < 1e-9)
    #expect(pacer.wait(now: 5.02) == 0)
}
