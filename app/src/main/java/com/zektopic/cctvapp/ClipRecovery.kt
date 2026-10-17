package com.zektopic.cctvapp

import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/** What a finalised MP4 should look like, from what the journal says was written to it. */
class ClipExpectation(val tracks: Int, val spanUs: Long)

/** What was actually found in an MP4 (see [ClipVerifier]). */
class ClipProbe(val tracks: Int, val tracksWithSamples: Int, val durationUs: Long?)

/** The rule that decides whether an MP4 can be trusted enough for its journal to be deleted. */
object ClipVerification {
    /** Shorter clips are not checked for duration: container rounding dominates. */
    private const val MIN_CHECKED_SPAN_US = 1_000_000L
    private const val MIN_TOLERANCE_US = 3_000_000L

    /**
     * How far the MP4's duration may be from the journal's first-to-last pts: 3 s or 10%. The check
     * is there to catch a missing index or a grossly short file, not drift: the container adds the
     * last sample's own duration and the two tracks end at different instants, a second or so
     * at most, so 3 s leaves margin without letting a half-written file through.
     */
    fun toleranceUs(expectedSpanUs: Long): Long = maxOf(MIN_TOLERANCE_US, expectedSpanUs / 10)

    /**
     * Whether a track's index reaches the end of the clip: the latest sample time seen while
     * walking its tail ([lastSampleUs], -1 if none) is within the tolerance of its [durationUs].
     * A track with no known duration only needs to have yielded something.
     */
    fun tailReached(lastSampleUs: Long, durationUs: Long?): Boolean {
        if (lastSampleUs < 0) return false
        if (durationUs == null) return true
        return lastSampleUs >= durationUs - toleranceUs(durationUs)
    }

    /** Null when [probe] matches [expect]; otherwise why not. */
    fun check(expect: ClipExpectation, probe: ClipProbe): String? {
        if (probe.tracks != expect.tracks) return "expected ${expect.tracks} tracks, found ${probe.tracks}"
        if (probe.tracksWithSamples != probe.tracks) {
            return "only ${probe.tracksWithSamples} of ${probe.tracks} tracks yield samples"
        }
        if (expect.spanUs >= MIN_CHECKED_SPAN_US) {
            val duration = probe.durationUs ?: return "no duration"
            if (abs(duration - expect.spanUs) > toleranceUs(expect.spanUs)) {
                return "duration ${duration / 1000} ms, journal covers ${expect.spanUs / 1000} ms"
            }
        }
        return null
    }
}

/** Reopens a finalised MP4 and checks it against [ClipExpectation]. Must be cheap: it never reads the file whole. */
interface ClipVerifier {
    /** Null when the file is as expected; otherwise a short reason. */
    fun verify(file: File, expect: ClipExpectation): String?
}

/** The Android half of turning a journal back into media: format objects from and to journal headers. */
interface ClipFormats {
    fun videoInfo(format: MediaFormat): JournalVideoInfo
    fun audioInfo(format: MediaFormat): JournalAudioInfo
    fun video(info: JournalVideoInfo): MediaFormat
    fun audio(info: JournalAudioInfo): MediaFormat
}

/**
 * Everything ClipRecorder needs to journal clips and verify and rebuild them. Left out (null),
 * the recorder behaves as before: no journal, and a finalise failure loses the clip.
 */
class ClipSafety(
    val formats: ClipFormats,
    val verifier: ClipVerifier,
    /** Opens a clip's journal; tests substitute one that fails. */
    val journalFactory: (File, JournalHeader) -> ClipJournalWriter = { file, header -> ClipJournalWriter(file, header) },
    /** Where rebuilds run: never the encoder callback thread, never under the recorder lock. */
    val executor: Executor = Executors.newSingleThreadExecutor { task ->
        // A rebuild reads and writes a whole clip: it must not compete with the encoder or the
        // stream for the CPU, so it runs at background priority.
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "clip-recovery").apply { isDaemon = true }
    }
)

/** Writes a journal back out as an MP4 with a fresh muxer, streaming so the clip is never held in memory. */
class JournalReplay(private val muxerFactory: (File) -> ClipMuxer, private val formats: ClipFormats) {

    class Result(val videoSamples: Int, val audioSamples: Int, val hadAudioTrack: Boolean)

    /** Throws if the journal cannot be read or the muxer fails; [out] is deleted in that case. */
    fun replay(journal: File, out: File, includeAudio: Boolean, scan: JournalScan): Result {
        val reader = ClipJournalReader.open(journal) ?: throw IOException("journal unreadable")
        var muxer: ClipMuxer? = null
        try {
            reader.use {
                val m = muxerFactory(out)
                muxer = m
                val header = reader.header
                val videoTrack = m.addTrack(formats.video(header.video))
                val audioInfo = header.audio
                val withAudio = includeAudio && audioInfo != null && scan.audioSamples > 0
                val audioTrack = if (withAudio) m.addTrack(formats.audio(audioInfo!!)) else -1
                m.start()
                var lastVideo = Long.MIN_VALUE
                var lastAudio = Long.MIN_VALUE
                var videoSamples = 0
                var audioSamples = 0
                while (true) {
                    val s = reader.next() ?: break
                    // The same non-decreasing rule the live writer applied per track.
                    if (s.isVideo) {
                        if (s.ptsUs <= lastVideo) continue
                        m.writeSample(videoTrack, s.data, s.ptsUs, s.keyFrame)
                        lastVideo = s.ptsUs
                        videoSamples++
                    } else if (withAudio) {
                        if (s.ptsUs <= lastAudio) continue
                        m.writeSample(audioTrack, s.data, s.ptsUs, false)
                        lastAudio = s.ptsUs
                        audioSamples++
                    }
                }
                if (videoSamples == 0) throw IOException("journal holds no video")
                m.stop()
                return Result(videoSamples, audioSamples, withAudio)
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
        }
    }
}

/**
 * The rebuild ladder: replay the journal with every track, then video-only, and accept a result
 * only once [ClipVerifier] passes it. Anything else leaves just the journal. The journal is
 * deleted only after a verified rebuild; every other outcome leaves it on disk.
 */
class ClipRecovery(
    private val replay: JournalReplay,
    private val verifier: ClipVerifier,
    /** Moves a file; replaceable so tests can make it fail. */
    private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
) {

    enum class Outcome { REBUILT, REBUILT_VIDEO_ONLY, FAILED }

    class Result(
        val outcome: Outcome,
        val scan: JournalScan?,
        val videoSamples: Int = 0,
        val audioSamples: Int = 0,
        val hadAudioTrack: Boolean = false,
        /** Why each failed step failed, for the log and the event. */
        val error: String? = null
    ) {
        val rebuilt: Boolean get() = outcome != Outcome.FAILED
    }

    companion object {
        private const val TAG = "ClipRecovery"
    }

    /**
     * Puts the verified [temp] at [target]. The existing target is only ever moved aside, never
     * deleted first: if the move fails it is put back, so [target] is the old file or the new one.
     */
    private fun place(temp: File, target: File): Boolean {
        if (rename(temp, target)) return true
        if (!target.exists()) return false
        val backup = File(target.parentFile, target.name + ".orig")
        backup.delete()
        if (!rename(target, backup)) return false
        if (rename(temp, target)) {
            backup.delete()
            return true
        }
        rename(backup, target)
        return false
    }

    /** Rebuilds [journal] into [target]; on success [target] is the verified MP4 and [journal] is gone. */
    fun recover(journal: File, target: File): Result {
        val scan = ClipJournalReader.scan(journal)
            ?: return Result(Outcome.FAILED, null, error = "journal unreadable")
        if (scan.videoSamples == 0) return Result(Outcome.FAILED, scan, error = "journal holds no video")

        val temp = File(target.parentFile, target.name + ".rebuild")
        val errors = mutableListOf<String>()
        val full = scan.header.audio != null && scan.audioSamples > 0
        for (withAudio in if (full) listOf(true, false) else listOf(false)) {
            val step = if (withAudio) "with audio" else "video only"
            try {
                val r = replay.replay(journal, temp, withAudio, scan)
                val expect = ClipExpectation(
                    tracks = if (r.hadAudioTrack) 2 else 1,
                    spanUs = if (r.hadAudioTrack) scan.spanUs else scan.videoSpanUs
                )
                val bad = verifier.verify(temp, expect)
                if (bad == null) {
                    if (!place(temp, target)) {
                        // Verified, but there is nowhere to put it: keep the good scratch file and the
                        // journal and report, rather than attach a path to a file that is not there.
                        val why = "$step: rebuilt clip verified but could not be moved into place; kept as ${temp.name}"
                        Log.e(TAG, why)
                        return Result(Outcome.FAILED, scan, error = (errors + why).joinToString("; "))
                    }
                    journal.delete()
                    Log.i(TAG, "Rebuilt ${target.name} from its journal ($step)")
                    return Result(
                        if (r.hadAudioTrack) Outcome.REBUILT else Outcome.REBUILT_VIDEO_ONLY,
                        scan, r.videoSamples, r.audioSamples, r.hadAudioTrack,
                        error = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
                    )
                }
                errors.add("$step: rebuilt clip failed verification ($bad)")
            } catch (e: Exception) {
                Log.e(TAG, "Rebuild $step failed for ${journal.name}", e)
                errors.add("$step: ${ClipFailure.detailOf(e)}")
            }
            temp.delete()
        }
        Log.w(TAG, "Could not rebuild ${target.name}; keeping ${journal.name}: ${errors.joinToString("; ")}")
        return Result(Outcome.FAILED, scan, error = errors.joinToString("; "))
    }
}
