package com.zektopic.cctvapp

/**
 * Whether a clip's [android.media.MediaMuxer] can be started yet.
 *
 * `addTrack()` must happen for every track before `start()`, and video and audio formats
 * arrive from the encoder asynchronously and in either order. Starting on video alone when
 * audio is still coming would leave the clip silent forever (the muxer is already started,
 * so a late audio format can no longer be added); waiting for an audio format that will never
 * arrive -- audio off, or the RECORD_AUDIO permission missing -- would mean no clip ever
 * starts.
 *
 * The fix is to not guess: [audioExpected] is told, not inferred (from whatever decided
 * whether to prepareAudio()/disableAudio() and setOnlyVideo() on the stream itself -- see
 * ClipRecorder.setAudioExpected). With that, readiness is a pure function of what has
 * arrived so far, no timeout involved.
 *
 * Pure logic with no Android types, so it is covered by JVM unit tests.
 */
object TrackReadiness {
    fun ready(hasVideoFormat: Boolean, audioExpected: Boolean, hasAudioFormat: Boolean): Boolean =
        hasVideoFormat && (!audioExpected || hasAudioFormat)
}
