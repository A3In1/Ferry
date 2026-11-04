import Foundation
import FerryLogic
import Testing

/// What a writer would put on the wire, draining everything queued so far.
private func drain(_ o: Outbox) async -> [UInt8] {
    o.finish()
    var wire: [UInt8] = []
    for await item in o.items {
        switch item {
        case let .data(d): wire += d
        case let .latest(slot, gen): if let d = o.take(slot: slot, gen: gen) { wire += d }
        }
    }
    return wire
}

@Test func latestWinsWhileWaiting() async {
    let o = Outbox()
    o.send(Data([1]))
    for m: UInt8 in 10...15 { o.sendLatest(Data([m]), slot: 0) } // moves while the writer is busy
    o.sendLatest(Data([90]), slot: 1) // an ACK
    o.sendLatest(Data([91]), slot: 1)
    #expect(await drain(o) == [1, 15, 91]) // only the newest of each, no backlog
}

@Test func ordinaryMessagesKeepOrder() async {
    let o = Outbox()
    o.sendLatest(Data([10]), slot: 0) // move
    o.send(Data([2])) // press: the move goes first
    o.sendLatest(Data([11]), slot: 0) // move after the press
    o.sendLatest(Data([12]), slot: 0)
    o.send(Data([3])) // release
    #expect(await drain(o) == [10, 2, 12, 3])
}
