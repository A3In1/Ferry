import Foundation
import FerryLogic
import Testing

@Test func ackAndStatsWire() throws {
    #expect(Fsp.ack(pts: 0x0102) == Data([0x17, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 1, 2]))
    let payload: [UInt8] = [0x07, 0xD0, 0, 120, 0, 118, 0, 2, 0, 0x7A, 0x12, 0, 0, 0x72, 0x70, 0xE0, 0, 62, 0, 3, 0, 124, 0, 41]
    var p = FspParser()
    let got = try p.push(Fsp.magic + Fsp.message(0x05, payload) + Fsp.message(0x05, payload + [9, 9])) // trailing bytes ignored
    let s = Fsp.PhoneStats(windowMs: 2000, encoded: 120, sent: 118, dropped: 2, targetBps: 8_000_000, outBps: 7_500_000,
                           encodeTenthMs: 62, writeTenthMs: 3, rttTenthMs: 124, rttMinTenthMs: 41)
    #expect(got == [.stats(s), .stats(s)])
    #expect(throws: Fsp.ProtocolError.self) { var q = FspParser(); _ = try q.push(Fsp.magic + Fsp.message(0x05, [0, 1])) }
}

@Test func statsWindow() {
    var st = ScreenStats(now: 0)
    // 60 fps for 2 s; transit (recv − pts on different clocks) 5000 µs, every 10th frame 20 ms late.
    for i in 0..<120 {
        let pts = UInt64(1_000_000 + i * 16_667)
        let late: UInt64 = i % 10 == 9 ? 20_000 : 0
        st.received(pts: pts, bytes: 10_000, config: false, at: pts + 5_000 + late)
        st.shown(after: 2_000)
    }
    st.received(pts: 0, bytes: 30, config: true, at: 10) // config: counted, no delay sample
    st.skippedBehind()
    st.dropped(2)
    #expect(st.report(at: 1_999_999) == nil)
    let line = st.report(at: 2_000_000)!
    #expect(line.contains("received 60.5 fps (4.80 Mbps), shown 60.0 fps, behind 1, dropped 2"))
    #expect(line.contains("recv→shown 2.0 ms (max 2.0)"))
    #expect(line.contains("delay +2.0 ms (max 20.0, jitter 3.9)")) // 12 of 120 frames 20 ms above best; 23 jumps of 20 ms over 119 gaps
    #expect(line.hasSuffix("| phone: no STATS"))

    st.phone(.init(windowMs: 2000, encoded: 120, sent: 120, dropped: 0, targetBps: 8_000_000, outBps: 4_000_000,
                   encodeTenthMs: 60, writeTenthMs: Fsp.PhoneStats.unknown, rttTenthMs: 100, rttMinTenthMs: 40))
    st.received(pts: 5_000_000, bytes: 1, config: false, at: 5_005_000)
    st.shown(after: 1_000)
    let next = st.report(at: 4_000_000)!
    #expect(next.contains("phone: encoded 60 fps, sent 60, dropped 0, 4.00 of 8.00 Mbps, capture→encoded 6.0 ms, write - ms, write→ack 10.0 ms (min 4.0)"))
    #expect(next.hasSuffix("est. capture→shown ≈ 9 ms")) // 6 + 4/2 + 0 + 1
}
