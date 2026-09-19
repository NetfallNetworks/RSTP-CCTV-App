package com.zektopic.cctvapp

/**
 * What a single-range `Range: bytes=…` header asks of a file of a given length.
 * Multi-range requests are answered with the whole file, which the spec allows.
 */
sealed class ByteRange {
    object Whole : ByteRange()
    object Unsatisfiable : ByteRange()
    /** Inclusive on both ends. */
    data class Span(val start: Long, val end: Long) : ByteRange()

    companion object {
        private val PATTERN = Regex("""^bytes=(\d*)-(\d*)$""")

        fun parse(header: String?, length: Long): ByteRange {
            val match = header?.trim()?.let { PATTERN.find(it) } ?: return Whole
            val (a, b) = match.destructured
            if (length <= 0) return Unsatisfiable
            return when {
                a.isEmpty() && b.isEmpty() -> Whole
                // "-n": the last n bytes.
                a.isEmpty() -> {
                    val n = b.toLong()
                    if (n == 0L) Unsatisfiable else Span((length - n).coerceAtLeast(0), length - 1)
                }
                else -> {
                    val start = a.toLong()
                    val end = (b.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
                    if (start >= length || start > end) Unsatisfiable else Span(start, end)
                }
            }
        }
    }
}
