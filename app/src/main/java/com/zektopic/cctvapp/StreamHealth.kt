package com.zektopic.cctvapp

/**
 * CctvServerService's own source of truth for whether the RTSP stream is genuinely up,
 * kept separate from the RootEncoder library's own `StreamBase.isStreaming` flag.
 *
 * That library flag is optimistic: `startStream()` sets it to `true` *before* it has
 * opened the camera or bound the RTSP port, and if that setup throws -- as it does when
 * Android denies camera access to a foreground service started from the background,
 * see [BootCameraAccessPolicy] -- nothing ever resets it. A failed boot-time start was
 * observed this way: `rtspServerCamera.isStreaming` stayed `true` forever, and `/status`
 * (which read that flag directly) reported `"streaming": true` while nothing was
 * listening on 8554 at all. A monitor trusting that field calls a dead camera healthy.
 *
 * Kept free of Android imports so the state transitions are unit-testable on the JVM,
 * the same way [BootStartPolicy] and [BootCameraAccessPolicy] are.
 */
class StreamHealth {

    @Volatile private var confirmedStreaming = false

    /** The message from the most recent failed start attempt; null once healthy. */
    @Volatile var lastError: String? = null
        private set

    /** True only from a start attempt that actually completed, until the next stop or failure. */
    val isStreaming: Boolean get() = confirmedStreaming

    /** Call this once a start attempt has returned without throwing. */
    fun markStarted() {
        confirmedStreaming = true
        lastError = null
    }

    /** Call this for a deliberate stop -- not a failure, so no error is recorded. */
    fun markStopped() {
        confirmedStreaming = false
        lastError = null
    }

    /** Call this when a start attempt throws. [reason] is surfaced on /status for diagnosis. */
    fun markFailed(reason: String?) {
        confirmedStreaming = false
        lastError = reason?.takeIf { it.isNotBlank() } ?: "unknown error"
    }
}
