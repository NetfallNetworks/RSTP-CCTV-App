package com.zektopic.cctvapp

/**
 * Reads a number out of a MediaFormat-like source that may hold it as an int, as a float, or not
 * at all. MediaFormat keys such as KEY_FRAME_RATE are set as either by different encoders, and
 * `getInteger` throws on a float; building a journal header must never throw over an optional
 * value. Plain Kotlin so it is covered by JVM tests; AndroidClipFormats feeds it the real getters.
 */
object FormatNumbers {
    /** [has] false: [default]. Otherwise the int, else the float rounded, else [default]. */
    fun read(has: Boolean, asInt: () -> Int, asFloat: () -> Float, default: Int = -1): Int {
        if (!has) return default
        try {
            return asInt()
        } catch (_: Exception) {
        }
        return try {
            val f = asFloat()
            if (f.isNaN() || f.isInfinite()) default else Math.round(f)
        } catch (_: Exception) {
            default
        }
    }
}