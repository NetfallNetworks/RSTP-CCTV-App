package com.zektopic.cctvapp

import kotlin.math.abs
import kotlin.math.ceil

/**
 * The invariant that was missing and let a channel-mask bug ship silently: how many AAC
 * frames a clip's audio track *should* contain, given how long it really ran and what
 * sample rate it was captured at, versus how many it actually got.
 *
 * This deliberately does not take channel count as an input. An AAC frame carries
 * [SAMPLES_PER_FRAME] samples *per channel*, and a sample rate is likewise expressed per
 * channel, so the expected frame count for a given wall-clock duration is the same whether
 * the source is mono or stereo -- only the byte size of each frame differs. That is exactly
 * why this check catches what a container-duration comparison cannot: a clip whose audio
 * was captured mono but muxed under a stereo channel mask (see CctvServerService's
 * prepareAudio() call) has every *other* mono sample folded into an L/R pair instead of its
 * own frame, halving the true frame count -- while the container's reported duration, which
 * comes from the wall-clock pts written per frame and not from how many samples got
 * encoded, keeps agreeing with the video track to the millisecond. A real clip
 * (`2026-09-21T07-37-47_..._175432a5.mp4`) shipped with a container duration of 600.3s
 * (from `duration_ts 26,473,405` at `time_base 1/44100`) holding only 12,865 AAC frames --
 * 298.7s of actual audio, playing back at roughly 2x speed.
 *
 * Pure and Android-free, so it's covered by a JVM unit test; there is no way to reproduce
 * the underlying channel-mask/hardware mismatch itself off-device (see the PR description
 * for the on-device ffprobe check that plays the same role against a real recorded clip).
 */
object AudioSampleAccounting {
    const val SAMPLES_PER_FRAME = 1024

    /**
     * How many AAC frames a clip should hold if [durationUs] of audio was really captured
     * at [sampleRateHz], independent of channel count (see class doc).
     */
    fun expectedFrameCount(
        durationUs: Long,
        sampleRateHz: Int,
        samplesPerFrame: Int = SAMPLES_PER_FRAME
    ): Long {
        require(durationUs >= 0) { "durationUs must be non-negative, was $durationUs" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(samplesPerFrame > 0) { "samplesPerFrame must be positive, was $samplesPerFrame" }
        val durationSec = durationUs.toDouble() / 1_000_000.0
        return ceil(durationSec * sampleRateHz / samplesPerFrame).toLong()
    }

    /**
     * True when [observedFrameCount] is within [toleranceRatio] of [expectedFrameCount] for
     * [durationUs] of audio at [sampleRateHz]. A channel-mask mismatch -- mono samples read
     * back as stereo frames, or vice versa -- misses by roughly 2x (or 0.5x), far outside
     * any tolerance a partial trailing frame or muxer flush timing would ever need.
     */
    fun frameCountConsistent(
        durationUs: Long,
        sampleRateHz: Int,
        observedFrameCount: Long,
        samplesPerFrame: Int = SAMPLES_PER_FRAME,
        toleranceRatio: Double = 0.05
    ): Boolean {
        val expected = expectedFrameCount(durationUs, sampleRateHz, samplesPerFrame)
        if (expected == 0L) return observedFrameCount == 0L
        val ratio = observedFrameCount.toDouble() / expected.toDouble()
        return abs(ratio - 1.0) <= toleranceRatio
    }
}
