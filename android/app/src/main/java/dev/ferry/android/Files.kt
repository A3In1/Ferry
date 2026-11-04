package dev.ferry.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import dev.ferry.core.FfiEvent
import dev.ferry.core.FfiFsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

const val FILES_TAG = "FerryFiles"

/** The Files grant: a paired Mac lists folders under shared storage and asks for files (FsListRequest / FsGet). */
object PhoneFiles {
    const val MAX_ENTRIES = 2000
    /** Most files one folder download sends; past this the rest is left out (and the user told). */
    const val MAX_TREE_FILES = 10_000
    const val MAX_DEPTH = 32

    /** Why this build/phone can't browse at all, or null. */
    fun unavailable(): String? = when {
        !Flavor.ALL_FILES -> "Browsing phone files needs the full version of Ferry"
        Build.VERSION.SDK_INT < 30 -> "Browsing phone files needs Android 11 or newer" // shortcut: no MANAGE_EXTERNAL_STORAGE on 10; use legacy storage if 10 matters
        else -> null
    }

    fun accessGranted() = Build.VERSION.SDK_INT >= 30 && Flavor.ALL_FILES && Environment.isExternalStorageManager()

    /** All files access for Ferry; some OEMs lack the per-app screen, so fall back to the list. */
    @RequiresApi(30)
    fun askAccess(ctx: Context, launch: (Intent) -> Unit) {
        try {
            launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${ctx.packageName}".toUri()))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * [rel] (slash-separated, relative, "" = root) under [root] → its canonical file (symlinks resolved),
     * or null if it is absolute, has a `..` part, or ends up outside [root].
     */
    fun resolve(root: File, rel: String): File? {
        if (rel.startsWith("/") || rel.startsWith("\\") || '\u0000' in rel) return null
        val parts = rel.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) return null
        val base = root.canonicalFile
        val f = File(base, parts.joinToString(File.separator)).canonicalFile
        return f.takeIf { it == base || it.path.startsWith(base.path + File.separator) }
    }

    /** The answer to FsListRequest: visible entries, folders first, then by name, at most [MAX_ENTRIES]. */
    fun list(root: File, rel: String): FfiEvent.FsList {
        fun fail(why: String) = FfiEvent.FsList(rel, emptyList(), why)
        val dir = resolve(root, rel) ?: return fail("Not allowed: $rel")
        if (!dir.isDirectory) return fail("Not a folder: $rel")
        val files = dir.listFiles() ?: return fail("Can't read $rel")
        // Stat each file once up front: sorting on File.isDirectory stats it on every comparison
        // (hundreds of thousands of syscalls for a big camera folder).
        val entries = files.filter { !it.name.startsWith(".") }
            .map { f -> val dir = f.isDirectory; FfiFsEntry(f.name, dir, if (dir) 0u else f.length().toULong(), f.lastModified()) to f.name.lowercase() }
            .sortedWith(compareBy({ !it.first.dir }, { it.second }, { it.first.name }))
            .take(MAX_ENTRIES)
            .map { it.first }
        return FfiEvent.FsList(rel, entries, "")
    }

    /** Validated regular files for FsGet, plus the paths that were refused. */
    fun files(root: File, paths: List<String>): Pair<List<File>, List<String>> {
        val ok = paths.associateWith { resolve(root, it)?.takeIf(File::isFile) }
        return ok.values.filterNotNull() to ok.filterValues { it == null }.keys.toList()
    }

    /**
     * FsGet paths (files or folders) → files to send, each named relative to the requested item's
     * parent ("DCIM/Camera/x.jpg" for "DCIM"), so the Mac rebuilds the folder. Hidden entries are
     * skipped inside folders, symlinked folders aren't followed (no loops, no escapes), and at most
     * [max] files are taken ([Tree.capped] says some were left out).
     */
    fun tree(root: File, paths: List<String>, max: Int = MAX_TREE_FILES): Tree {
        val base = root.canonicalFile
        val out = ArrayList<Pair<File, String>>()
        val refused = ArrayList<String>()
        var capped = false
        fun inside(f: File) = f.path.startsWith(base.path + File.separator)
        fun walk(dir: File, rel: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val kids = dir.listFiles() ?: return Unit.also { Log.w(FILES_TAG, "can't read $rel") }
            for (k in kids.sortedBy { it.name }) {
                if (k.name.startsWith(".")) continue
                if (out.size >= max) { capped = true; return }
                val c = k.canonicalFile
                // a symlink resolves elsewhere: skip it (loops, or a way out of shared storage)
                if (c.path != File(dir, k.name).path || !inside(c)) continue
                when {
                    c.isDirectory -> walk(c, "$rel/${k.name}", depth + 1)
                    c.isFile -> out += c to "$rel/${k.name}"
                }
            }
        }
        for (p in paths) {
            val f = resolve(root, p)
            when {
                f == null || f == base -> refused += p
                f.isFile -> if (out.size < max) out += f to f.name else capped = true
                f.isDirectory -> walk(f, f.name, 1)
                else -> refused += p
            }
        }
        return Tree(out, refused, capped)
    }

    class Tree(val files: List<Pair<File, String>>, val refused: List<String>, val capped: Boolean)

    /** Why a Mac's request is refused right now, or null. */
    private fun refused(): String? = unavailable() ?: when {
        Grant.FILES !in Grants -> "Turn on Files in Ferry on the phone"
        !accessGranted() -> "Allow All files access for Ferry on the phone"
        else -> null
    }

    private fun root(): File = Environment.getExternalStorageDirectory()

    fun onList(peerId: String, rel: String) = Ferry.scope.launch(Dispatchers.IO) {
        val answer = refused()?.let { FfiEvent.FsList(rel, emptyList(), it) } ?: list(root(), rel)
        if (answer.error.isNotEmpty()) Log.w(FILES_TAG, "list refused: ${answer.error}")
        Outbox.send(peerId, answer)
    }

    fun onGet(ctx: Context, peerId: String, paths: List<String>) = Ferry.scope.launch(Dispatchers.IO) {
        refused()?.let { Log.w(FILES_TAG, "get refused: $it"); return@launch }
        val peer = Ferry.peers.value.firstOrNull { it.id == peerId } ?: return@launch
        val root = root()
        val singles = paths.filter { resolve(root, it)?.isFile == true }
        val (ok, _) = files(root, singles)
        // Single files: open now, the fd pins the file we validated (a later symlink swap can't redirect the send).
        val opened = ok.mapNotNull { f ->
            runCatching { Source(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), f.name) }
                .onFailure { Log.w(FILES_TAG, "can't open ${f.name}: ${it.text()}") }.getOrNull()
        }
        // Folders (and anything else, refused there): canonical paths the core opens itself, named with their folder.
        val tree = tree(root, paths - singles.toSet())
        val sources = opened + tree.files.map { (f, rel) -> Source(null, rel, f.path) }
        val failed = tree.refused.size + ok.size - opened.size
        Log.w(FILES_TAG, "get ${paths.size} path(s): sending ${sources.size} file(s), refused $failed, capped ${tree.capped}")
        if (failed > 0) Ferry.toast(ctx, "Couldn't send ${count(failed, "item")} to ${peer.name}" + (tree.refused.firstOrNull()?.let { ": $it" } ?: ""))
        if (tree.capped) Ferry.toast(ctx, "That folder is very big: sending the first ${"%,d".format(MAX_TREE_FILES)} files to ${peer.name}")
        if (sources.isNotEmpty()) Ferry.send(ctx, peer, sources)
    }
}
