package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DimmerTest {
    private class FakeSetting(var value: Brightness?, var writable: Boolean = true) : BrightnessSetting {
        val writes = mutableListOf<Brightness>()
        override fun read() = value
        override fun write(b: Brightness): Boolean {
            if (!writable) return false
            writes += b
            value = b
            return true
        }
    }

    /** Survives a "crash": a new Dimmer over the same store. */
    private class FakeSaved : SavedBrightness {
        var value: Brightness? = null
        override fun load() = value
        override fun save(b: Brightness) { value = b }
        override fun clear() { value = null }
    }

    private val auto = Brightness(140, 1)

    @Test
    fun dimThenRestorePutsBackExactlyWhatTheUserHad() {
        val setting = FakeSetting(auto)
        val saved = FakeSaved()
        val d = Dimmer(setting, saved)
        assertTrue(d.dim())
        assertEquals(Brightness.DIM, setting.value)
        assertEquals(auto, saved.value)
        assertEquals(auto, d.restore())
        assertEquals(auto, setting.value)
        assertNull(saved.value)
        assertNull(d.restore()) // nothing left: no write
        assertEquals(listOf(Brightness.DIM, auto), setting.writes)
    }

    @Test
    fun crashWhileDimmedRestoresOnTheNextStart() {
        val setting = FakeSetting(auto)
        val saved = FakeSaved()
        Dimmer(setting, saved).dim()
        // process dies; the next service start:
        assertEquals(auto, Dimmer(setting, saved).restore())
        assertEquals(auto, setting.value)
    }

    @Test
    fun dimmingAgainAfterACrashKeepsTheRealOriginal() {
        val setting = FakeSetting(auto)
        val saved = FakeSaved()
        Dimmer(setting, saved).dim()
        val next = Dimmer(setting, saved)
        assertTrue(next.dim()) // current is our dim; must not become "the user's"
        assertEquals(auto, saved.value)
        assertEquals(auto, next.restore())
    }

    @Test
    fun notAllowedChangesNothingAndKeepsTheSavedValue() {
        val setting = FakeSetting(auto, writable = false)
        val saved = FakeSaved()
        val d = Dimmer(setting, saved)
        assertFalse(d.dim())
        assertNull(d.restore()) // write refused: still saved for next time
        assertEquals(auto, saved.value)
        setting.writable = true
        assertEquals(auto, d.restore())
        assertNull(saved.value)
        assertFalse(Dimmer(FakeSetting(null), FakeSaved()).dim()) // unreadable setting: don't touch it
    }
}
