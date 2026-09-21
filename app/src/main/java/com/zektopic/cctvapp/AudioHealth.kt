package com.zektopic.cctvapp

/**
 * Two decisions about whether audio is actually working right now, as opposed to merely
 * configured to. Both are pure and expressed in the same encoder-clock pts space
 * ClipRecorder already uses everywhere else (video presentation time), so neither depends
 * on wall-clock time and both are deterministic to test.
 *
 * [TrackReadiness] answers "has what we're waiting for arrived"; this answers "do we still
 * believe it, this long after" -- the gap a purely-told `audioExpected` flag cannot close on
 * its own, since being told audio is coming does not guarantee it keeps coming (a
 * microphone can fail after prepareAudio() reports success, or never produce a format at
 * all despite being asked for -- see ClipRecorder's use of both).
 *
 * Plain logic with no Android types, so it is covered by JVM unit tests.
 */
object AudioHealth {
    /**
     * True once [elapsedSinceVideoStartUs] -- video pts elapsed since this encoder
     * session's first frame -- has passed [timeoutUs] with still no audio format seen.
     * Past that point, audio is not coming this session: recording silent video is
     * strictly better than waiting forever and recording nothing.
     */
    fun formatTimedOut(elapsedSinceVideoStartUs: Long, timeoutUs: Long): Boolean =
        elapsedSinceVideoStartUs >= timeoutUs

    /**
     * True when the newest audio sample seen ([newestAudioPtsUs]) is recent enough,
     * relative to [nowUs] (the video pts a clip is starting at), to trust adding an audio
     * track to a fresh clip's muxer. A MediaMuxer track added but never given a single
     * sample throws on stop() -- a clip should only get an audio track when audio looks
     * alive right now, not merely "was configured and has arrived at some point in the
     * past".
     */
    fun flowing(newestAudioPtsUs: Long?, nowUs: Long, staleAfterUs: Long): Boolean =
        newestAudioPtsUs != null && nowUs - newestAudioPtsUs < staleAfterUs
}
