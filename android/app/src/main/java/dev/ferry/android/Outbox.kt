package dev.ferry.android

import android.os.SystemClock
import android.util.Log
import dev.ferry.core.FfiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fire-and-forget event sending to paired Macs. One lane per peer: a bounded queue drained by
 * one coroutine, so events to a peer stay in order and callers never wait on the network.
 * A failed send (up to ~10 s for an unreachable peer) parks the peer for 30 s, dropping its events.
 */
object Outbox {
    private const val CAP = 64
    private const val DOWN_MS = 30_000L

    private class Lane {
        val q = ArrayDeque<FfiEvent>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile var downUntil = 0L
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    /** To every paired Mac. Callers check the grant first. */
    fun broadcast(event: FfiEvent) = Ferry.peers.value.forEach { send(it.id, event) }

    fun send(peerId: String, event: FfiEvent) {
        val lane = lanes.computeIfAbsent(peerId) { Lane().also { drain(peerId, it) } }
        if (SystemClock.elapsedRealtime() < lane.downUntil) return
        synchronized(lane.q) { pushBounded(lane.q, event, CAP) { it is FfiEvent.Notification } }
        lane.wake.trySend(Unit)
    }

    private fun drain(peerId: String, lane: Lane) = Ferry.scope.launch {
        for (u in lane.wake) {
            while (true) {
                val e = synchronized(lane.q) { lane.q.removeFirstOrNull() } ?: break
                val node = Ferry.node.value ?: continue // stopped: drop
                try {
                    node.sendEvent(peerId, e)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    lane.downUntil = SystemClock.elapsedRealtime() + DOWN_MS
                    synchronized(lane.q) { lane.q.clear() }
                    Log.w(TAG, "event to ${peerId.take(8)} failed, dropping its events for 30 s: ${ex.text()}")
                }
            }
        }
    }

    /** Adds [e]; when full, drops the oldest [droppable] item (else the oldest item). */
    fun <T> pushBounded(q: ArrayDeque<T>, e: T, cap: Int, droppable: (T) -> Boolean) {
        if (q.size >= cap) {
            val i = q.indexOfFirst(droppable)
            q.removeAt(if (i >= 0) i else 0)
        }
        q.addLast(e)
    }
}
