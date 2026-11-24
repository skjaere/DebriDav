package io.skjaere.debridav.collection.hls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.ceil

/**
 * A release as HLS (fragmented-MP4 segments), for browsers: FFmpeg copies its video and one audio track into segments
 * cut at the keyframes its [index] lists, about [targetSegmentSeconds] apart. Audio the browser doesn't decode is
 * converted to AAC stereo; the video is never converted. Segments are made
 * as the player asks for them: one FFmpeg run makes them in order from where it starts, at most [aheadSegments] past
 * the last one asked for; a segment asked for that the run won't reach soon starts a new run there, and a player
 * halfway through the segments made ahead starts one where they end. Segments far behind the player are deleted.
 * Closing the stream stops FFmpeg; its files go with the session's folder.
 *
 * Runs after the first start a keyframe early and drop the piece before their first segment: audio stored just before
 * a keyframe lives in the cluster before it, and starting there puts it in the segment it belongs to, as a run from the
 * start does. So segments from different runs join exactly, every packet once. Converted audio joins exactly too: each
 * run's AAC frames start on the same grid, 1024 samples apart from 0 (see [convertedAudio]).
 */
class HlsStream(
    private val ffmpeg: String,
    private val sourceUri: String,
    private val index: MatroskaIndex,
    /** The audio track, and whether it is converted; none for a release without audio. */
    private val audio: AudioChoice?,
    private val dir: Path,
    private val scope: CoroutineScope,
    targetSegmentSeconds: Double = TARGET_SEGMENT_SECONDS,
    private val aheadSegments: Int = AHEAD_SEGMENTS,
    private val keepBehind: Int = KEEP_BEHIND_SEGMENTS,
    private val waitTimeoutMillis: Long = WAIT_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HlsStream::class.java)
    private val lock = Mutex()
    private val runs = AtomicInteger()
    /** The segments made so far, and whether the stream closed: what a request for a segment waits on. */
    private data class State(val made: Set<Int> = emptySet(), val closed: Boolean = false)

    private val state = MutableStateFlow(State())

    @Volatile
    private var current: Run? = null

    @Volatile
    private var lastAsked = 0

    private val closed: Boolean get() = state.value.closed

    /** Whether FFmpeg is making segments now. */
    internal val running: Boolean get() = current?.alive == true

    /** How many times FFmpeg was started. */
    internal val runsStarted: Int get() = runs.get()

    /** Where segment i starts, in seconds; the last entry is where the release ends. */
    private val bounds: List<Double> = boundsOf(index, targetSegmentSeconds)

    val segmentCount: Int get() = bounds.size - 1

    fun playlist(): String = buildString {
        val longest = (0 until segmentCount).maxOf { bounds[it + 1] - bounds[it] }
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
        appendLine("#EXT-X-TARGETDURATION:${ceil(longest).toInt()}")
        appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
        for (i in 0 until segmentCount) {
            appendLine(String.format(Locale.ROOT, "#EXTINF:%.6f,", bounds[i + 1] - bounds[i]))
            appendLine("seg$i.m4s")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    /** The init segment (the MP4 header every segment's fragments go with), once a run has made it. */
    suspend fun init(): Path? {
        val init = dir.resolve(INIT)
        if (init.exists()) return init
        lock.withLock { if (current?.alive != true) start(firstMissingFrom(lastAsked)) }
        return withTimeoutOrNull(waitTimeoutMillis) {
            while (!init.exists() && !closed) delay(POLL_MILLIS)
            init.takeIf { it.exists() }
        }
    }

    /**
     * Segment [number], made now if it isn't yet; null if it can't be (no such segment, the stream closed, FFmpeg
     * failed).
     */
    suspend fun segment(number: Int): Path? {
        if (number !in 0 until segmentCount || closed) return null
        lastAsked = number
        lock.withLock {
            val run = current
            val reached = segmentPath(number).exists()
            val coming = run != null && run.alive && number >= run.first && number <= run.next.get() + aheadSegments
            if (!reached && !coming) {
                start(number)
            } else if (reached && run?.alive != true) {
                // Not at once: a run started now would be as far ahead as allowed after a segment, and stop. Once the
                // player is halfway through what's made, a run has half the way to go.
                val missing = firstMissingFrom(number + 1)
                if (missing < segmentCount && missing - number <= aheadSegments / 2) start(missing)
            }
        }
        val ready = withTimeoutOrNull(waitTimeoutMillis) { state.first { number in it.made || it.closed } }
        deleteBehind(number)
        return segmentPath(number).takeIf { ready != null && it.exists() }
    }

    override fun close() {
        state.update { it.copy(closed = true) }
        current?.stop()
        current = null
    }

    private fun segmentPath(number: Int): Path = dir.resolve("seg$number.m4s")

    private fun firstMissingFrom(number: Int): Int =
        (number until segmentCount).firstOrNull { !segmentPath(it).exists() } ?: segmentCount

    /** Starts FFmpeg making segments from [first] on, in place of the run before. */
    private fun start(first: Int) {
        if (closed || first >= segmentCount) return
        current?.stop()
        val run = Run(runs.incrementAndGet(), first)
        current = run
        run.launch()
    }

    private fun deleteBehind(number: Int) {
        val oldest = number - keepBehind
        if (oldest <= 0) return
        dir.listDirectoryEntries("seg*.m4s").forEach { path ->
            val n = path.name.removePrefix("seg").removeSuffix(".m4s").toIntOrNull()
            if (n != null && n < oldest) {
                Files.deleteIfExists(path)
                state.update { it.copy(made = it.made - n) }
            }
        }
    }

    /** One FFmpeg run, making segments [first] onwards. */
    private inner class Run(val id: Int, val first: Int) {
        private val workDir: Path = Files.createDirectories(dir.resolve("run-$id"))
        private val list: Path = workDir.resolve("segments.csv")

        /** The next segment this run will finish. */
        val next = AtomicInteger(first)

        @Volatile
        private var process: Process? = null
        private var watcher: Job? = null

        val alive: Boolean get() = process?.isAlive == true || watcher?.isActive == true

        fun launch() {
            // A run after the first starts at the keyframe before its first segment, which it drops (see the class).
            val start = if (first == 0) bounds.first() else index.keyframes.last { it < bounds[first] }
            // Where the run's audio starts: there, or where FFmpeg is asked to seek to.
            val audioFrom = if (first == 0) start else start + SEEK_PAST_KEYFRAME
            val splits = if (first == 0) bounds.subList(1, segmentCount) else bounds.subList(first, segmentCount)
            val command = buildList {
                addAll(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-fflags", "+bitexact"))
                // FFmpeg seeks a stream with B-frames to a point a little before the one asked for:
                // ask past the keyframe.
                if (first > 0) addAll(listOf("-ss", seconds(start + SEEK_PAST_KEYFRAME)))
                addAll(listOf("-copyts", "-i", sourceUri, "-map", "0:v:0"))
                audio?.let { addAll(listOf("-map", "0:a:${it.stream}?")) }
                addAll(listOf("-c:v", "copy"))
                when {
                    audio == null -> Unit
                    audio.convert -> addAll(convertedAudio(audioFrom))
                    // An MP4 fragment can't start before 0: audio stored before (encoders' priming, often) is left out.
                    else -> addAll(listOf("-c:a", "copy", "-bsf:a", DROP_BEFORE_ZERO))
                }
                addAll(listOf("-avoid_negative_ts", "disabled", "-f", "segment"))
                // Split times count from where the run starts, a little early: the cut falls on the first keyframe at
                // or after one, so a start FFmpeg puts a hair later can't push it past its keyframe.
                if (splits.isNotEmpty()) {
                    addAll(listOf("-segment_times", splits.joinToString(",") { seconds(it - start - SPLIT_EARLY) }))
                }
                addAll(listOf("-segment_start_number", (if (first == 0) 0 else first - 1).toString()))
                addAll(listOf("-reset_timestamps", "0"))
                addAll(listOf("-segment_list", list.toString(), "-segment_list_type", "csv"))
                addAll(listOf("-segment_format", "mp4", "-segment_format_options", MP4_OPTIONS))
                add(workDir.resolve("seg%d.mp4").toString())
            }
            process = ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("ffmpeg.log").toFile()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            log.info("hls: run {} making segments from {} of {}", id, first, segmentCount)
            watcher = scope.launch { watch() }
        }

        fun stop() {
            process?.let { if (it.isAlive) it.destroyForcibly() }
            watcher?.cancel()
        }

        /** Takes each segment FFmpeg lists as done: the init header kept once, the fragments as the segment. */
        private suspend fun watch() {
            var listed = 0
            try {
                while (scope.isActive) {
                    val exited = process?.isAlive != true
                    val done = if (list.exists()) Files.readAllLines(list).filter { it.isNotBlank() } else emptyList()
                    for (line in done.drop(listed)) {
                        // Far enough ahead of the player: what FFmpeg made past that goes with this run's folder.
                        if (next.get() > lastAsked + aheadSegments) {
                            log.debug("hls: run {} is {} segments ahead; stopping", id, aheadSegments)
                            process?.destroyForcibly()
                            return
                        }
                        take(line.substringBefore(','))
                        listed++
                    }
                    if (exited) break
                    delay(POLL_MILLIS)
                }
            } finally {
                process?.let { if (it.isAlive) it.destroyForcibly() }
                workDir.toFile().deleteRecursively()
            }
        }

        private fun take(file: String) {
            val number = file.removePrefix("seg").removeSuffix(".mp4").toIntOrNull() ?: return
            val raw = workDir.resolve(file)
            if (raw.exists()) take(number, raw)
        }

        /** Segment [number] from [raw], the file FFmpeg made it in. */
        private fun take(number: Int, raw: Path) {
            val (header, fragments) = split(Files.readAllBytes(raw))
            Files.deleteIfExists(raw)
            // The piece before a later run's first segment only took the audio stored before it.
            if (first > 0 && number < first) return
            val init = dir.resolve(INIT)
            if (!init.exists()) atomically(init, header)
            atomically(segmentPath(number), fragments)
            next.set(number + 1)
            state.update { it.copy(made = it.made + number) }
        }
    }

    /**
     * FFmpeg's options converting the audio to AAC stereo, for a run whose audio starts at [from] seconds. The AAC
     * frames of every run fall on one grid, 1024 samples apart from 0, so segments made by different runs meet without
     * a gap or an overlap: the encoder starts at the first point of the grid past [from], trimmed or padded with silence
     * to it. Never at 0 itself: the encoder's first frame comes a frame before the audio, and a fragment can't start
     * before 0.
     */
    private fun convertedAudio(from: Double): List<String> {
        val frame = AAC_FRAME_SAMPLES.toDouble() / AAC_SAMPLE_RATE
        val first = ceil(maxOf(from, frame) / frame).toLong() * AAC_FRAME_SAMPLES
        return listOf(
            "-af", "aresample=$AAC_SAMPLE_RATE:async=1:first_pts=$first",
            "-c:a", "aac", "-b:a", AAC_BITRATE, "-ac", "2",
        )
    }

    private fun atomically(path: Path, bytes: ByteArray) {
        val partial = path.resolveSibling("${path.name}.part")
        Files.write(partial, bytes)
        Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val INIT = "init.mp4"
        private const val TARGET_SEGMENT_SECONDS = 6.0
        private const val AHEAD_SEGMENTS = 10
        private const val KEEP_BEHIND_SEGMENTS = 30
        private const val WAIT_TIMEOUT_MILLIS = 120_000L
        private const val POLL_MILLIS = 50L
        private const val SEEK_PAST_KEYFRAME = 0.2
        private const val SPLIT_EARLY = 0.01
        private const val AAC_SAMPLE_RATE = 48_000
        private const val AAC_FRAME_SAMPLES = 1024
        private const val AAC_BITRATE = "192k"

        /** Drops audio packets stored before 0. */
        private const val DROP_BEFORE_ZERO = "noise=drop=lt(pts\\,0)"
        private const val BOX_HEADER = 8
        private const val BOX_TYPE_OFFSET = 4
        private const val BOX_TYPE_LENGTH = 4
        private const val UINT32_MASK = 0xFFFFFFFFL

        /**
         * Fragmented MP4 whose fragments carry their own absolute times (frag_discont, and no shifting of timestamps),
         * with signed composition offsets so B-frames need no decode delay, and the header written once the first
         * packets are in (AC-3 and E-AC-3 need them for theirs).
         */
        private const val MP4_OPTIONS = "movflags=+frag_keyframe+empty_moov+default_base_moof+frag_discont" +
            "+negative_cts_offsets+delay_moov:video_track_timescale=90000:avoid_negative_ts=disabled"

        /**
         * Where segments start: the first keyframe, then each keyframe at least [target] seconds after the last start.
         */
        fun boundsOf(index: MatroskaIndex, target: Double): List<Double> {
            val starts = mutableListOf(index.keyframes.first())
            index.keyframes.drop(1).forEach { if (it - starts.last() >= target) starts += it }
            // A last stretch shorter than half a segment joins the one before.
            if (starts.size > 1 && index.durationSeconds - starts.last() < target / 2) starts.removeAt(starts.lastIndex)
            return starts + index.durationSeconds
        }

        private fun seconds(value: Double) = String.format(Locale.ROOT, "%.6f", value)

        /** An MP4's header boxes (ftyp, moov) and its fragments (moof, mdat): the init and the segment. */
        fun split(mp4: ByteArray): Pair<ByteArray, ByteArray> {
            val buffer = ByteBuffer.wrap(mp4)
            val header = java.io.ByteArrayOutputStream()
            val fragments = java.io.ByteArrayOutputStream()
            var position = 0
            while (position + BOX_HEADER <= mp4.size) {
                var size = buffer.getInt(position).toLong() and UINT32_MASK
                val type = String(mp4, position + BOX_TYPE_OFFSET, BOX_TYPE_LENGTH, Charsets.US_ASCII)
                if (size == 1L) size = buffer.getLong(position + BOX_HEADER)
                if (size == 0L) size = (mp4.size - position).toLong()
                if (size < BOX_HEADER || position + size > mp4.size) break
                when (type) {
                    "ftyp", "moov" -> header.write(mp4, position, size.toInt())
                    "moof", "mdat" -> fragments.write(mp4, position, size.toInt())
                }
                position += size.toInt()
            }
            return header.toByteArray() to fragments.toByteArray()
        }
    }
}
