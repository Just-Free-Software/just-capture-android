package fyi.justfreesoftware.justcapture.services.recording

/**
 * Mixes interleaved stereo mic input down to mono.
 *
 * Normally that's a plain average of the two channels. But a phone's mics
 * can fail or be covered (a drop, a case, lint), and averaging a working
 * channel with a dead one halves the volume. So each channel's level is
 * tracked (in decibels, so a channel that suddenly dies is noticed within a
 * second or two however loud it was before), and when one stays far quieter
 * than the other the mix uses the louder channel alone. Switches are crossfaded over
 * one buffer and need a clear margin, so they're rare and inaudible.
 */
class StereoDownmixer {

    enum class Mode(val leftWeight: Float, val rightWeight: Float) {
        MIX(0.5f, 0.5f),
        LEFT_ONLY(1f, 0f),
        RIGHT_ONLY(0f, 1f)
    }

    var mode = Mode.MIX
        private set

    /** Smoothed channel levels in dB (10·log10 of mean-square sample value). */
    var leftLevelDb = 0.0
        private set
    var rightLevelDb = 0.0
        private set
    private var buffersSeen = 0

    /**
     * Mixes [frames] stereo frames from [stereo] (L, R, L, R, …) into [mono].
     * Returns true if the mode changed during this buffer.
     */
    fun mix(stereo: ShortArray, frames: Int, mono: ShortArray): Boolean {
        if (frames <= 0) return false

        var sumL = 0.0
        var sumR = 0.0
        for (i in 0 until frames) {
            val l = stereo[2 * i].toDouble()
            val r = stereo[2 * i + 1].toDouble()
            sumL += l * l
            sumR += r * r
        }
        updateLevels(sumL / frames, sumR / frames)

        val previous = mode
        mode = chooseMode()

        // Crossfade from the old weights to the new ones across this buffer.
        val fromL = previous.leftWeight
        val fromR = previous.rightWeight
        val toL = mode.leftWeight
        val toR = mode.rightWeight
        for (i in 0 until frames) {
            val t = if (previous == mode) 1f else (i + 1).toFloat() / frames
            val wl = fromL + (toL - fromL) * t
            val wr = fromR + (toR - fromR) * t
            val v = wl * stereo[2 * i] + wr * stereo[2 * i + 1]
            mono[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return previous != mode
    }

    private fun updateLevels(meanSquareL: Double, meanSquareR: Double) {
        buffersSeen++
        // The mic often clicks as it opens; don't let that skew the levels.
        if (buffersSeen <= IGNORED_BUFFERS) return
        // Plain running average while warming up, so the first decision rests
        // on real signal; then the slow moving average.
        val warmup = buffersSeen - IGNORED_BUFFERS
        val weight = if (warmup <= WARMUP_BUFFERS) 1.0 / warmup else SMOOTHING
        leftLevelDb += weight * (toDb(meanSquareL) - leftLevelDb)
        rightLevelDb += weight * (toDb(meanSquareR) - rightLevelDb)
    }

    private fun chooseMode(): Mode {
        if (buffersSeen < IGNORED_BUFFERS + WARMUP_BUFFERS) return mode
        // Both channels near digital silence: nothing to judge by, so keep
        // whatever we're doing.
        if (maxOf(leftLevelDb, rightLevelDb) < MIN_LEVEL_DB) return mode
        val difference = kotlin.math.abs(leftLevelDb - rightLevelDb)
        val louderMode = if (leftLevelDb >= rightLevelDb) Mode.LEFT_ONLY else Mode.RIGHT_ONLY
        return when (mode) {
            // Drop a channel only when it's far quieter than the other...
            Mode.MIX -> if (difference > ENTER_SINGLE_DB) louderMode else Mode.MIX
            // ...and only bring it back once it's clearly contributing again.
            else -> when {
                difference < EXIT_SINGLE_DB -> Mode.MIX
                else -> louderMode
            }
        }
    }

    private companion object {
        /** Per-buffer smoothing for a ~1 s time constant at ~47 buffers/s (1024 frames at 48 kHz). */
        const val SMOOTHING = 1.0 / 47

        /** ~100 ms skipped at the start, then ~400 ms averaged before the first decision. */
        const val IGNORED_BUFFERS = 5
        const val WARMUP_BUFFERS = 20

        /** Drop a channel once it's 12 dB quieter; bring it back within 6 dB. */
        const val ENTER_SINGLE_DB = 12.0
        const val EXIT_SINGLE_DB = 6.0

        /** Below an RMS of ~3 (of 32767), both channels are effectively silent. */
        const val MIN_LEVEL_DB = 9.5

        fun toDb(meanSquare: Double) = 10 * kotlin.math.log10(meanSquare + 1e-3)
    }
}
