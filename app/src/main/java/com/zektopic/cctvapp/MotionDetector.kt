package com.zektopic.cctvapp

import android.graphics.Bitmap
import kotlin.math.abs

class MotionDetector(
    private val sampleSize: Int = 96,
    thresholdRatio: Double = DEFAULT_THRESHOLD_RATIO,
    private val thresholdDelta: Int = 20,
    /**
     * How many analysed frames back the second comparison reaches; at the default two
     * snapshots a second, 4 is two seconds.
     */
    private val lookbackFrames: Int = DEFAULT_LOOKBACK_FRAMES
) {
    companion object {
        const val DEFAULT_THRESHOLD_RATIO = 0.08
        const val DEFAULT_LOOKBACK_FRAMES = 4

        /** Sensitivity 1 (least twitchy) maps to this ratio. */
        private const val LEAST_SENSITIVE_RATIO = 0.30
        /**
         * Sensitivity 10 (most twitchy) maps to this ratio. Low on purpose: a cat on the
         * far side of the frame covers well under 1% of a 96x96 sample, and missing an
         * animal costs more than a false clip. Measured on the Echo Show 5 in a still,
         * dim room, frame-to-frame noise stayed under 0.1% even 5 s apart.
         */
        private const val MOST_SENSITIVE_RATIO = 0.003

        /**
         * Maps a 1..10 user-facing sensitivity onto the fraction of the frame that has
         * to change before it counts as motion. Higher sensitivity => lower threshold.
         *
         * Geometric, not linear: the useful thresholds span two orders of magnitude, and a
         * linear scale spent nine of its ten steps above 3%, where small animals never
         * register. Each step is now the same factor (~1.67x) apart.
         */
        fun sensitivityToThresholdRatio(sensitivity: Int): Double {
            val clamped = sensitivity.coerceIn(1, 10)
            val position = (clamped - 1) / 9.0
            return LEAST_SENSITIVE_RATIO * Math.pow(MOST_SENSITIVE_RATIO / LEAST_SENSITIVE_RATIO, position)
        }

        /**
         * The larger of the change against the previous frame and against the oldest
         * frame in [history].
         *
         * Comparing only consecutive frames, half a second apart, misses slow movement: an
         * animal creeping across the patio changes almost nothing between two snapshots,
         * but a good deal over two seconds. [history] is oldest first.
         */
        fun changedRatioAgainstHistory(history: List<IntArray>, current: IntArray, thresholdDelta: Int): Double {
            if (history.isEmpty()) return 0.0
            val recent = changedRatio(history.last(), current, thresholdDelta)
            if (history.size == 1) return recent
            return maxOf(recent, changedRatio(history.first(), current, thresholdDelta))
        }

        /**
         * Fraction of samples that differ by at least [thresholdDelta] between two
         * equally sized luma buffers.
         *
         * Pulled out of the Bitmap path on purpose: this is the part worth unit testing,
         * and it needs no Android framework to run.
         */
        fun changedRatio(previous: IntArray, current: IntArray, thresholdDelta: Int): Double {
            require(previous.size == current.size) { "luma buffers must be the same size" }
            if (current.isEmpty()) return 0.0
            var changed = 0
            for (i in current.indices) {
                if (abs(current[i] - previous[i]) >= thresholdDelta) changed++
            }
            return changed.toDouble() / current.size.toDouble()
        }

        /** ITU-R BT.601 luma, integer arithmetic. */
        fun lumaOf(pixels: IntArray): IntArray {
            val luma = IntArray(pixels.size)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[i] = (r * 30 + g * 59 + b * 11) / 100
            }
            return luma
        }
    }

    @Volatile
    private var thresholdRatio: Double = thresholdRatio

    /** The last [lookbackFrames] analysed frames, oldest first. */
    private val history = ArrayDeque<IntArray>()

    fun updateSensitivity(sensitivity: Int) {
        thresholdRatio = sensitivityToThresholdRatio(sensitivity)
    }

    fun detectMotion(bitmap: Bitmap): Double {
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        try {
            val pixels = IntArray(sampleSize * sampleSize)
            scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)

            val luma = lumaOf(pixels)
            val ratio = changedRatioAgainstHistory(history, luma, thresholdDelta)
            history.addLast(luma)
            while (history.size > lookbackFrames) history.removeFirst()
            return ratio
        } finally {
            // createScaledBitmap may return the source itself when no scaling is needed;
            // recycling that would destroy the caller's bitmap mid-pipeline.
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    fun isMotionDetected(bitmap: Bitmap): Pair<Boolean, Double> {
        val ratio = detectMotion(bitmap)
        return Pair(ratio >= thresholdRatio, ratio)
    }
}
