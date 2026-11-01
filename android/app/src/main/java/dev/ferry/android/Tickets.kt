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

/** For people, not logs: see [Errors.friendly]. */
fun Throwable.friendly(): String = Errors.friendly(text())

object Errors {
    private const val REACH = "Couldn't reach your Mac. Check that Ferry is open on it and both are on the same Wi-Fi, or plug in the USB cable."

    /** (any of these, lowercase) → what to tell the user. First match wins. */
    private val known = listOf(
        listOf("invalid, expired or used ticket", "no ticket is shown") to
            "That QR code has expired or was already used. On your Mac, choose Pair Your Phone… (or Pair Another Phone…) to show a new one.",
        listOf("not a ferry pairing ticket", "bad ticket", "ticket has no", "malformed ticket", "ticket name", "percent escape") to
            "That isn't a Ferry pairing code. On your Mac, choose Pair Your Phone… (or Pair Another Phone…) and scan the code it shows.",
        listOf("declined on the other device") to "Pairing was declined on your Mac.",
        listOf("declined on this device") to "Pairing was cancelled on this phone.",
        listOf("not paired") to "Your Mac doesn't recognise this phone any more. Forget it here, then pair again.",
        listOf("blake3 mismatch", "shorter than its resume", "shrank while sending", "ended after") to
            "The file changed or was damaged on the way. Try sending it again.",
        listOf("no space", "enospc", "disk full") to "There isn't enough free storage.",
        listOf("permission denied", "eacces") to "Ferry isn't allowed to read that file.",
        listOf("timed out", "went away", "connection lost", "connect to the other device", "not acknowledged", "connection refused",
            "unreachable", "no route", "closed") to REACH,
    )

    /** A core or Android error message → plain text for a toast or notification (the raw text goes to the log). */
    fun friendly(raw: String): String {
        val s = raw.removePrefix("reason=").trim()
        val low = s.lowercase()
        known.firstOrNull { (keys, _) -> keys.any { it in low } }?.let { return it.second }
        if (s.isEmpty()) return "Something went wrong. Try again."
        return s.replaceFirstChar { it.uppercase() }.take(200)
    }
}

/** "1 file", "3 files". */
fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

/** "A", "A and B", "A, B and C". */
fun joinAnd(items: List<String>) = if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString() + " and " + items.last()

/** [runCatching] for suspend code: lets coroutine cancellation through instead of swallowing it. */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
