package dev.ferry.android.screen

// Encoder format choices, pure (no Android calls) so they're unit-tested on the JVM.

/** How much of the low-latency format an encoder gets; the next tier is tried if configure refuses one. */
enum class Tier {
    /** Everything below plus the latency keys: KEY_LOW_LATENCY, KEY_LATENCY 1, vendor keys, boosted operating rate, capped pts gap. */
    TUNED,

    /** Realtime priority, operating rate = fps, no B-frames, VBR, profile (the format before TUNED existed). */
    LOW_LATENCY,

    /** Only the keys every encoder takes. */
    PLAIN,
}

object Tuning {
    /**
     * H.264 profile to ask for (MediaCodecInfo.CodecProfileLevel values: 1 Baseline, 0x10000 ConstrainedBaseline,
     * 8 High). High (CABAC, 8x8 transform) is ~10-20% fewer bits for the same picture, and with no B-frames it
     * has no reordering delay; smaller frames are less Wi-Fi airtime per frame. Kept a constant (and logged) so a
     * device can be measured with ConstrainedBaseline by changing one line.
     */
    const val PROFILE = 8

    /** Qualcomm's low-latency mode (c2.qti encoders): no input batching/lookahead in the firmware. */
    const val QTI_LOW_LATENCY = "vendor.qti-ext-enc-low-latency.enable"

    /** The operating rate asked for at most. */
    const val MAX_OPERATING_RATE = 120

    fun profileName(p: Int) = when (p) {
        1 -> "Baseline"
        0x10000 -> "ConstrainedBaseline"
        2 -> "Main"
        8 -> "High"
        0x80000 -> "ConstrainedHigh"
        else -> "profile $p"
    }

    /** The highest level the encoder offers for [profile] ([levels] = profile to level pairs), or null if it lacks it. */
    fun level(levels: List<Pair<Int, Int>>, profile: Int = PROFILE): Int? =
        levels.filter { it.first == profile }.maxOfOrNull { it.second }

    /**
     * The operating rate for [tier]: TUNED asks for up to 2x the frame rate (as far as the encoder says it can go at
     * this size, [maxFps]). Vendors set clocks from it: at operating rate = fps the encoder is clocked to just
     * keep up, so each frame takes nearly a whole frame time; at 2x it takes about half. Otherwise = fps.
     */
    fun operatingRate(tier: Tier, fps: Int, maxFps: Double?): Int {
        if (tier != Tier.TUNED) return fps
        val cap = maxFps?.takeIf { it.isFinite() }?.toInt() ?: return fps
        return minOf(fps * 2, MAX_OPERATING_RATE, cap).coerceAtLeast(fps)
    }

    /**
     * KEY_MAX_PTS_GAP_TO_ENCODER: the encoder sees at most one frame interval between frames (output pts are
     * restored by the framework). Without it the first frame after a still second gets a second's worth of
     * bits from rate control: a big frame that is slow to encode and to send, right when a scroll starts.
     */
    fun maxPtsGapUs(fps: Int) = 1_000_000L / fps

    /** Vendor keys to set, from the encoder's supported vendor parameters (API 31+; null = unknown). */
    fun vendorKeys(supported: List<String>?): Map<String, Int> =
        if (supported != null && QTI_LOW_LATENCY in supported) mapOf(QTI_LOW_LATENCY to 1) else emptyMap()

    /** The supported vendor parameters worth seeing in the log. */
    fun latencyParams(supported: List<String>?) = supported.orEmpty().filter { "latency" in it || "low-delay" in it }
}
