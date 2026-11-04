package dev.ferry.android.screen

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

/** Brightness as Settings.System holds it: SCREEN_BRIGHTNESS (0-255) and SCREEN_BRIGHTNESS_MODE (0 manual, 1 auto). */
data class Brightness(val level: Int, val mode: Int) {
    companion object {
        const val MANUAL = 0 // Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        /** The lowest setting; the framework clamps it to the panel's minimum (the backlight stays on). */
        val DIM = Brightness(0, MANUAL)
    }
}

/** The system's brightness setting. */
interface BrightnessSetting {
    fun read(): Brightness?
    /** Level first, then mode (so auto comes back last, on the restored level). False if not allowed. */
    fun write(b: Brightness): Boolean
}

/** Where the user's brightness waits while dimmed: persisted, so a crash or restart still restores it. */
interface SavedBrightness {
    fun load(): Brightness?
    fun save(b: Brightness)
    fun clear()
}

/** Dim while mirroring and put back exactly what the user had (pure, unit-tested). */
class Dimmer(private val setting: BrightnessSetting, private val saved: SavedBrightness) {
    /**
     * Saves the user's brightness, then goes to the lowest, manual. If something is already saved (a session that
     * crashed while dimmed) that is the user's real setting: keep it, the current one is ours. False if not dimmed.
     */
    fun dim(): Boolean {
        val now = setting.read() ?: return false
        if (saved.load() == null) saved.save(now) // before touching anything
        return setting.write(Brightness.DIM)
    }

    /** Puts the saved brightness back and forgets it; null if nothing was saved (or writing failed: kept for next time). */
    fun restore(): Brightness? {
        val b = saved.load() ?: return null
        if (!setting.write(b)) return null
        saved.clear()
        return b
    }
}

/** "Dim phone screen while mirroring" over Settings.System (needs WRITE_SETTINGS). */
object ScreenDim {
    private const val LEVEL = "savedBrightness"
    private const val MODE = "savedBrightnessMode"

    fun allowed(ctx: Context) = Settings.System.canWrite(ctx)

    private fun dimmer(ctx: Context): Dimmer {
        val cr = ctx.contentResolver
        val prefs = ctx.getSharedPreferences("screen", Context.MODE_PRIVATE)
        return Dimmer(
            object : BrightnessSetting {
                override fun read() = runCatching {
                    Brightness(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS), Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE))
                }.getOrNull()

                override fun write(b: Brightness) = runCatching {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, b.level) &&
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, b.mode)
                }.onFailure { Log.w(SCREEN_TAG, "can't change brightness", it) }.getOrDefault(false)
            },
            object : SavedBrightness {
                override fun load() = if (prefs.contains(LEVEL)) Brightness(prefs.getInt(LEVEL, 0), prefs.getInt(MODE, 0)) else null
                override fun save(b: Brightness) = prefs.edit(commit = true) { putInt(LEVEL, b.level).putInt(MODE, b.mode) }
                override fun clear() = prefs.edit(commit = true) { remove(LEVEL).remove(MODE) }
            },
        )
    }

    /** Dims if the setting is on and allowed. True if dimmed. */
    fun dim(ctx: Context): Boolean {
        if (!ScreenPrefs.dim.value || !allowed(ctx)) return false
        val d = dimmer(ctx)
        return d.dim().also { if (it) Log.w(SCREEN_TAG, "dimmed the phone screen") }
    }

    /** Restores a saved brightness, if any: at the end of a session, and on service start after a crash. Any thread. */
    fun restore(ctx: Context) {
        dimmer(ctx).restore()?.let { Log.w(SCREEN_TAG, "brightness restored to ${it.level} (${if (it.mode == Brightness.MANUAL) "manual" else "auto"})") }
    }
}
