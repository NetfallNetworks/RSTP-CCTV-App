package com.zektopic.cctvapp

/**
 * "Stop & pause auto": for a while, detection does not start clips. Manual recording
 * still works, and detections still extend a clip that is already open.
 */
class AutoPause(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var untilMs = 0L

    val isPaused: Boolean get() = clock() < untilMs

    val pausedUntilMs: Long? get() = if (isPaused) untilMs else null

    fun pauseFor(minutes: Int) {
        untilMs = maxOf(untilMs, clock() + minutes * 60_000L)
    }

    fun resume() {
        untilMs = 0L
    }
}
