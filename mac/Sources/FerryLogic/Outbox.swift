import Foundation
import Synchronization

/// An ordered outgoing queue where some messages are "latest wins" (cursor moves, ACKs): while one is
/// waiting for the writer, a newer one replaces it instead of queueing behind it, so a slow link never
/// leaves a backlog of stale moves to replay. Any ordinary message sent after a latest-wins one takes it
/// along first, so order between kinds holds. Any thread.
public final class Outbox: Sendable {
    public enum Item: Sendable {
        case data(Data)
        /// Write whatever `take(slot:gen:)` returns now (nil = already sent with an ordinary message).
        case latest(slot: Int, gen: UInt64)
    }

    private struct State {
        var pending: [Int: Data] = [:]
        var queued: Set<Int> = []
        var gen: UInt64 = 0
    }

    private let state = Mutex(State())
    private let out: AsyncStream<Item>.Continuation
    /// What the writer reads, in order.
    public let items: AsyncStream<Item>

    public init() {
        (items, out) = AsyncStream.makeStream(of: Item.self)
    }

    public func send(_ data: Data) {
        state.withLock { s in
            // Older latest-wins messages go first; their queued markers become no-ops.
            for slot in s.pending.keys.sorted() { out.yield(.data(s.pending[slot]!)) }
            s.pending = [:]
            s.queued = []
            s.gen &+= 1
            out.yield(.data(data))
        }
    }

    public func sendLatest(_ data: Data, slot: Int) {
        state.withLock { s in
            s.pending[slot] = data
            if s.queued.insert(slot).inserted { out.yield(.latest(slot: slot, gen: s.gen)) }
        }
    }

    /// The writer reached a `.latest` marker: the newest message for its slot, if still pending.
    public func take(slot: Int, gen: UInt64) -> Data? {
        state.withLock { s in
            guard s.gen == gen else { return nil }
            s.queued.remove(slot)
            return s.pending.removeValue(forKey: slot)
        }
    }

    public func finish() { out.finish() }
}
