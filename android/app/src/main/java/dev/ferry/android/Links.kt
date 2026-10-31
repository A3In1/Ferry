package dev.ferry.android

import java.net.URI

object Links {
    /** True for an absolute http(s) URL with a host; the only links the phone will open. */
    fun isWebUrl(s: String): Boolean {
        if (s.length > 8192 || s.any { it.isWhitespace() || it.isISOControl() }) return false
        val u = try {
            URI(s)
        } catch (e: Exception) {
            return false
        }
        val scheme = u.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !(u.host ?: u.rawAuthority).isNullOrEmpty() // authority: IDN hosts parse with host = null
    }

    /** Shared text that is exactly one web URL → that URL (sent as a Link); anything else → null (sent as Clipboard). */
    fun sharedUrl(text: String): String? = text.trim().takeIf(::isWebUrl)
}
