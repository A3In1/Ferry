import FerryLogic
import Foundation
import Testing

@Suite struct TransferProgressTests {
    let t0 = Date(timeIntervalSince1970: 1_000_000)

    @Test func sumsFilesInFlightAndCountsFiles() {
        var p = TransferProgress(names: ["a", "b", "c"], totalBytes: 300)
        #expect(p.fraction == 0)
        #expect(p.fileNumber == 1)
        p.progress(index: 0, done: 50, total: 100, at: t0)
        p.progress(index: 1, done: 30, total: 100, at: t0)
        #expect(p.bytesDone == 80)
        #expect(p.currentName == "b")
        p.progress(index: 0, done: 100, total: 100, at: t0)
        #expect(p.fileNumber == 2)
        p.finish(index: 1)
        #expect(p.fileNumber == 3)
        p.finish(index: 2)
        #expect(p.fileNumber == 3) // never "4 of 3"
    }

    @Test func unknownSizeIsIndeterminate() {
        #expect(TransferProgress(names: ["a"]).fraction == nil)
        #expect(TransferProgress(names: ["a"]).detail() == "")
    }

    @Test func speedIgnoresResumedHeadStart() {
        var p = TransferProgress(names: ["big"], totalBytes: 100_000_000)
        p.progress(index: 0, done: 60_000_000, total: 100_000_000, at: t0) // resumed at 60 MB
        #expect(p.speed(at: t0) == nil)
        p.progress(index: 0, done: 70_000_000, total: 100_000_000, at: t0 + 1)
        #expect(p.speed(at: t0 + 1) == 10_000_000)
        #expect(p.secondsLeft(at: t0 + 1) == 3)
        #expect(p.detail(at: t0 + 1) == "70 MB of 100 MB · 10.0 MB/s · 3 s left")
        #expect(p.speed(at: t0 + 10) == nil) // stalled
    }

    @Test func timeLeftWording() {
        #expect(timeLeft(0.2) == "1 s left")
        #expect(timeLeft(59) == "59 s left")
        #expect(timeLeft(61) == "2 min left")
        #expect(timeLeft(7200) == "about 2 h left")
    }

    @Test func transferIDsMatchTheCore() {
        let id = newTransferID()
        #expect(id.count == 32)
        #expect(id.allSatisfy { "0123456789abcdef".contains($0) })
    }
}
