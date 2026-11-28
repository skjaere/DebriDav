package io.skjaere.debridav.collection.hls

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.math.ceil

/** A file of an HLS stream, as asked for: [Ready], or why not. */
sealed interface HlsFile {
    data class Ready(val path: Path) : HlsFile

    /** No such file, or the stream closed: asking again won't make it. */
    data object Missing : HlsFile

    /**
     * Not made: the FFmpeg run that was to make it ended short of it (a read of the release broke off), or a seek
     * elsewhere replaced it. Asked for again, it's made: by a run started there if none will.
     */
    data object NotYet : HlsFile
}

/**
 * A release as HLS (fragmented-MP4 segments), for browsers: FFmpeg copies its video and one audio track into segments
 * cut at the keyframes its [index] lists, about [targetSegmentSeconds] apart. Audio the browser doesn't decode is
 * converted to AAC stereo; the video is never converted. Segments are made
 * as the player asks for them: one FFmpeg run makes them in order from where it starts, at most [aheadSegments] past
 * the last one asked for; a segment asked for that the run won't reach soon starts a new run there, and a player
 * halfway through the segments made ahead starts one where they end. A request waits while a run will make its segment,
 * and no longer: not once a seek replaced the run, or it ended short of it. Segments far behind the player are deleted.
 * Closing the stream stops FFmpeg, and waits until it and what takes its segments are gone; its files go with the
 * session's folder.
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
    /** Whether the video is HEVC: tagged hvc1, as Apple's players want it (FFmpeg's own hev1 they may not play). */
    private val hevc: Boolean = false,
    targetSegmentSeconds: Double = TARGET_SEGMENT_SECONDS,
    private val aheadSegments: Int = AHEAD_SEGMENTS,
    private val keepBehind: Int = KEEP_BEHIND_SEGMENTS,
    private val waitTimeoutMillis: Long = WAIT_TIMEOUT_MILLIS,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(HlsStream::class.java)
    /** Held to start or replace a run, and to close: never across a suspension. */
    private val lock = Any()
    private val runs = AtomicInteger()
    /**
     * The segments made so far, the run making more ([running], its id; [NONE] when none is), whether the init header
     * is made, and whether the stream closed: what a request waits on.
     */
    private data class State(
        val made: Set<Int> = emptySet(),
        val running: Int = NONE,
        val header: Boolean = false,
        val closed: Boolean = false,
    )

    private val state = MutableStateFlow(State())

    @Volatile
    private var current: Run? = null

    /** Every run started and not yet ended, the current one and any it replaced: what closing waits for. */
    private val live = ConcurrentHashMap.newKeySet<Run>()

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
    suspend fun init(): HlsFile {
        val init = dir.resolve(INIT)
        if (init.exists()) return HlsFile.Ready(init)
        val maker = synchronized(lock) { current?.takeIf { it.alive } ?: start(firstMissingFrom(lastAsked)) }
        // Any run makes it, with its first segment.
        val made = maker != null && awaitMade(maker, { it.header }) { true }
        return answer(made, init)
    }

    /** Segment [number], made now if it isn't yet. */
    suspend fun segment(number: Int): HlsFile {
        if (number !in 0 until segmentCount || closed) return HlsFile.Missing
        val maker = synchronized(lock) {
            // Where the player is, as a run deciding it's far enough ahead to stop reads it: under the same lock.
            lastAsked = number
            makerOf(number)
        }
        val made = maker == null || awaitMade(maker, { number in it.made }) { it.willMake(number) }
        val answer = answer(made, segmentPath(number))
        if (answer is HlsFile.Ready) deleteBehind(number)
        return answer
    }

    /** Stops FFmpeg, and returns once it and what takes its segments are gone: nothing of them writes after. */
    override fun close() {
        val ending = synchronized(lock) {
            state.update { it.copy(closed = true) }
            current = null
            live.toList()
        }
        ending.forEach(Run::stop)
        ending.forEach(Run::awaitEnd)
    }

    private fun answer(made: Boolean, path: Path): HlsFile = when {
        made && path.exists() -> HlsFile.Ready(path)
        closed -> HlsFile.Missing
        else -> HlsFile.NotYet
    }

    /** The run that will make segment [number], started if none will; none if it's made already. */
    private fun makerOf(number: Int): Run? {
        val run = current?.takeIf { it.alive }
        // Asked before looking for the file: a segment the run makes in between is found.
        val coming = run?.willMake(number) == true
        return when {
            segmentPath(number).exists() -> {
                if (run == null) startAhead(number)
                null
            }
            coming -> run
            else -> start(number)
        }
    }

    /**
     * Whether what's [ready] is made: by [maker], or a run that replaced it and [covers] it too. Not once the run that
     * would make it is replaced by one that won't, or ends short of it.
     */
    private suspend fun awaitMade(maker: Run, ready: (State) -> Boolean, covers: (Run) -> Boolean): Boolean =
        withTimeoutOrNull(waitTimeoutMillis) {
            var run: Run? = maker
            var made = false
            while (run != null && !made) {
                val id = run.id
                val now = state.first { ready(it) || it.closed || it.running != id }
                made = ready(now)
                run = if (made || now.closed) null else synchronized(lock) { current?.takeIf { it.alive && covers(it) } }
            }
            made
        } ?: false

    private fun segmentPath(number: Int): Path = dir.resolve("seg$number.m4s")

    private fun firstMissingFrom(number: Int): Int =
        (number until segmentCount).firstOrNull { !segmentPath(it).exists() } ?: segmentCount

    /**
     * After segment [number], made, with no run making more: not at once, as a run started now would be as far ahead
     * as allowed after a segment, and stop. Once the player is halfway through what's made, a run has half the way to
     * go.
     */
    private fun startAhead(number: Int) {
        val missing = firstMissingFrom(number + 1)
        if (missing < segmentCount && missing - number <= aheadSegments / 2) start(missing)
    }

    /** Starts FFmpeg making segments from [first] on, in place of the run before; none once closed. */
    private fun start(first: Int): Run? {
        if (closed || first >= segmentCount) return null
        current?.stop()
        val run = Run(runs.incrementAndGet(), first)
        current = run
        live += run
        state.update { it.copy(running = run.id) }
        run.launch()
        return run
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

        @Volatile
        private var watcher: Job? = null

        /** Stopped, or over: making no more, even while FFmpeg or the watcher are still going. */
        @Volatile
        private var stopped = false

        val alive: Boolean get() = !stopped && (process?.isAlive == true || watcher?.isActive == true)

        /** Whether this run is to make segment [number]: it hasn't yet, and will soon. */
        fun willMake(number: Int): Boolean = next.get().let { number in it..it + aheadSegments }

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
                // Read as what the probe found it to be, never as what its bytes look like (see FfprobeTrackProbe).
                addAll(listOf("-copyts", "-f", "matroska", "-i", sourceUri, "-map", "0:v:0"))
                audio?.let { addAll(listOf("-map", "0:a:${it.stream}?")) }
                addAll(listOf("-c:v", "copy"))
                if (hevc) addAll(listOf("-tag:v", "hvc1"))
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
            process = try {
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("ffmpeg.log").toFile()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: IOException) {
                over()
                throw e
            }
            log.info("hls: run {} making segments from {} of {}", id, first, segmentCount)
            watcher = scope.launch { watch() }
        }

        fun stop() {
            stopped = true
            process?.let { if (it.isAlive) it.destroyForcibly() }
            watcher?.cancel()
        }

        /** Once stopped, as the stream closes: waits until FFmpeg and the watcher are gone (it may never have run). */
        fun awaitEnd() {
            process?.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
            runBlocking { withTimeoutOrNull(TimeUnit.SECONDS.toMillis(STOP_WAIT_SECONDS)) { watcher?.join() } }
            over()
        }

        /** FFmpeg is gone: the run's folder goes, and a request waiting on the run is answered. */
        private fun over() {
            stopped = true
            workDir.toFile().deleteRecursively()
            live -= this
            state.update { if (it.running == id) it.copy(running = NONE) else it }
        }

        /** Takes each segment FFmpeg lists as done, once it's whole: the init header kept once, the fragments as it. */
        private suspend fun watch() {
            var taken = 0
            try {
                while (currentCoroutineContext().isActive) {
                    val exited = process?.isAlive != true
                    val done = if (list.exists()) Files.readAllLines(list).filter { it.isNotBlank() } else emptyList()
                    for (line in done.drop(taken)) {
                        if (farAhead()) return
                        val number = line.substringBefore(',').removePrefix("seg").removeSuffix(".mp4").toIntOrNull()
                        if (number != null && !whole(number)) break
                        number?.let(::take)
                        taken++
                    }
                    if (exited) break
                    delay(POLL_MILLIS)
                }
            } finally {
                process?.let {
                    if (it.isAlive) it.destroyForcibly()
                    it.waitFor(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
                }
                over()
            }
        }

        /**
         * Whether the run is far enough ahead of the player to stop; if it is, it's stopped. What FFmpeg made past that
         * goes with the run's folder. Under the lock, so a request for a segment this run would make either keeps it
         * going, or finds it stopped and starts another.
         */
        private fun farAhead(): Boolean = synchronized(lock) {
            (next.get() > lastAsked + aheadSegments).also { ahead ->
                if (ahead) {
                    log.debug("hls: run {} is {} segments ahead; stopping", id, aheadSegments)
                    stop()
                }
            }
        }

        /**
         * Whether segment [number], listed by FFmpeg as done, is whole. FFmpeg lists a segment as it starts the next, or
         * as its input ends: at the release's end, or where a read broke off, which leaves the segment cut short. That
         * one isn't kept: asked for, it's made by a new run. The release's last segment, ended by its input either way,
         * is kept.
         */
        private fun whole(number: Int): Boolean =
            number == segmentCount - 1 || workDir.resolve(fileOf(number + 1)).exists()

        private fun fileOf(number: Int) = "seg$number.mp4"

        private fun take(number: Int) {
            val raw = workDir.resolve(fileOf(number))
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
            state.update { it.copy(made = it.made + number, header = true) }
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
        private const val NONE = 0
        private const val STOP_WAIT_SECONDS = 5L
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
