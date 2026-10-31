package dev.ferry.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import java.util.concurrent.ConcurrentHashMap

/** Contact names for phone numbers via PhoneLookup; "" when unknown or without READ_CONTACTS. */
object Names {
    // shortcut: never invalidated while the process lives (a renamed contact keeps its old name); add a
    // ContactsContract ContentObserver if that matters.
    private val cache = ConcurrentHashMap<String, String>()

    fun of(ctx: Context, number: String): String {
        if (number.isBlank() || ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        cache[number]?.let { return it }
        val name = runCatching {
            val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull().orEmpty()
        if (cache.size > 1000) cache.clear()
        cache[number] = name
        return name
    }
}
