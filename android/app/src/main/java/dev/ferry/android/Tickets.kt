package dev.ferry.android

import kotlin.coroutines.cancellation.CancellationException

/** Pure helpers (unit-tested on the JVM). */
object Tickets {
    private const val PREFIX = "ferry://pair?"

    /** The pairing ticket in a deep link, scanned QR text or pasted text; null if it isn't one. */
    fun extract(text: String?): String? = text?.trim()?.takeIf { it.startsWith(PREFIX) && it.length > PREFIX.length }

    /**
     * "ip:port" as the core parses it (Rust `SocketAddr`): IPv6 in brackets, link-local
     * scope as the numeric interface index (`InetAddress` prints the interface name).
     */
    fun hostPort(host: String, scopeId: Int, port: Int): String {
        val h = host.substringBefore('%')
        if (':' !in h) return "$h:$port"
        return if (scopeId != 0) "[$h%$scopeId]:$port" else "[$h]:$port"
    }
}

fun Throwable.text(): String = message ?: toString()

/** [runCatching] for suspend code: lets coroutine cancellation through instead of swallowing it. */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
