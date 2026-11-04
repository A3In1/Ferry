package dev.ferry.android.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {
    @Test
    fun operatingRateIsBoostedOnlyWhenTunedAndWithinWhatTheEncoderCanDo() {
        assertEquals(120, Tuning.operatingRate(Tier.TUNED, 60, 240.0))
        assertEquals(90, Tuning.operatingRate(Tier.TUNED, 60, 90.5)) // the encoder's own limit at this size
        assertEquals(60, Tuning.operatingRate(Tier.TUNED, 60, 30.0)) // never below the frame rate
        assertEquals(60, Tuning.operatingRate(Tier.TUNED, 60, null))
        assertEquals(60, Tuning.operatingRate(Tier.TUNED, 60, Double.POSITIVE_INFINITY))
        assertEquals(60, Tuning.operatingRate(Tier.LOW_LATENCY, 60, 240.0))
        assertEquals(30, Tuning.operatingRate(Tier.PLAIN, 30, 240.0))
    }

    @Test
    fun profileLevelIsTheHighestOfferedForTheProfile() {
        val levels = listOf(1 to 0x200, 8 to 0x800, 8 to 0x4000, 0x10000 to 0x8000)
        assertEquals(0x4000, Tuning.level(levels, 8))
        assertEquals(0x8000, Tuning.level(levels, 0x10000))
        assertNull(Tuning.level(levels, 2))
        assertEquals("High", Tuning.profileName(Tuning.PROFILE))
    }

    @Test
    fun qtiLowLatencyKeyOnlyWhenListed() {
        val qti = listOf("vendor.qti-ext-enc-bitrate-mode.value", Tuning.QTI_LOW_LATENCY)
        assertEquals(mapOf(Tuning.QTI_LOW_LATENCY to 1), Tuning.vendorKeys(qti))
        assertTrue(Tuning.vendorKeys(listOf("vendor.other.x")).isEmpty())
        assertTrue(Tuning.vendorKeys(null).isEmpty())
        assertEquals(listOf(Tuning.QTI_LOW_LATENCY), Tuning.latencyParams(qti))
    }

    @Test
    fun ptsGapIsOneFrame() {
        assertEquals(16_666L, Tuning.maxPtsGapUs(60))
        assertEquals(33_333L, Tuning.maxPtsGapUs(30))
    }
}
