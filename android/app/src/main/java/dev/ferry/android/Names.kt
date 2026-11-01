package dev.ferry.android

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.PhoneLookup
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Contact names for phone numbers via PhoneLookup; "" when unknown or without READ_CONTACTS. */
object Names {
    /** Cleared whenever contacts change (see [watch]), so a new or renamed contact shows up. */
    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var watching = false

    fun of(ctx: Context, number: String): String = lookup(ctx)(number)

    /** For a batch of numbers: checks READ_CONTACTS once, not per number. */
    fun lookup(ctx: Context): (String) -> String {
        if (!ctx.granted(Manifest.permission.READ_CONTACTS)) return { "" }
        watch(ctx)
        return { number -> if (number.isBlank()) "" else cache[number] ?: find(ctx, number) }
    }

    /** Once per process (with READ_CONTACTS, which registering needs); lives as long as the cache. */
    private fun watch(ctx: Context) {
        if (watching) return
        synchronized(this) {
            if (watching) return
            try {
                ctx.applicationContext.contentResolver.registerContentObserver(
                    ContactsContract.AUTHORITY_URI, true,
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) = cache.clear()
                    },
                )
                watching = true
            } catch (e: SecurityException) {
                Log.w(PHONE_TAG, "can't watch contacts: ${e.text()}")
            }
        }
    }

    private fun find(ctx: Context, number: String): String {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = runCatching { ctx.firstString(uri, PhoneLookup.DISPLAY_NAME) }.getOrNull().orEmpty()
        if (cache.size > 1000) cache.clear()
        cache[number] = name
        return name
    }
}

/** Calls [row] for each row of the query until it returns false. */
inline fun Context.eachRow(
    uri: Uri, projection: Array<String>, selection: String? = null, args: Array<String>? = null, sort: String? = null,
    row: (Cursor) -> Boolean,
) {
    contentResolver.query(uri, projection, selection, args, sort)?.use { c -> while (c.moveToNext()) if (!row(c)) break }
}

/** [column] of the first row, or null. */
fun Context.firstString(uri: Uri, column: String): String? =
    contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

/** A Mac's requested row count: [default] for 0 or less, at most [max]. */
fun rowLimit(asked: Int, default: Int, max: Int) = if (asked <= 0) default else asked.coerceAtMost(max)
