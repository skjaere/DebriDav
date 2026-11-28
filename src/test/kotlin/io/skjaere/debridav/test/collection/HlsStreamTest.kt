package io.skjaere.debridav.test.collection

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.skjaere.debridav.collection.hls.AudioChoice
import io.skjaere.debridav.collection.hls.HlsFile
import io.skjaere.debridav.collection.hls.HlsStream
import io.skjaere.debridav.collection.hls.MatroskaIndex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A release as HLS: fragmented-MP4 segments cut at its keyframes by FFmpeg copying its video and audio, made as the
 * player asks for them. Joined in order, the segments must hold every packet of the source exactly once, in order,
 * whichever order they were asked for and however many FFmpeg runs made them. Audio the browser doesn't decode is
 * converted to AAC instead: its frames must follow each other without a gap or an overlap where one run's segments meet
 * another's, and keep time with the video. A release read from Usenet comes slowly, and a read can break off: served
 * that way ([ClipServer]), a request waits only while a run will make its segment, and a segment is kept only whole.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HlsStreamTest {

    private val clips: Path = Files.createTempDirectory("hls-clips")
    private lateinit var source: Path
    private lateinit var index: MatroskaIndex
    /** 30 s, E-AC-3 5.1 that beeps for 10 ms on each second, its first frames stored before 0 as FFmpeg's are. */
    private lateinit var eac3Source: Path
    private lateinit var eac3Index: MatroskaIndex
    /** 20 s of HEVC, with AAC. */
    private lateinit var hevcSource: Path
    private lateinit var hevcIndex: MatroskaIndex
    private lateinit var work: Path
    private lateinit var scope: CoroutineScope
    private val streams = mutableListOf<HlsStream>()

    @BeforeAll
    fun clip() = runBlocking<Unit> {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        source = clips.resolve("source.mkv")
        // 60 s, keyframes every 2 to 4 s, B-frames, and audio frames that don't line up with them: as a release is.
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24000/1001",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "60", "-c:v", "libx264", "-g", "96", "-keyint_min", "48", "-sc_threshold", "40", "-bf", "3",
            "-c:a", "aac", "-ac", "2", source.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg: $output" }
        index = assertNotNull(MatroskaIndex.read(MatroskaIndexTest.FileBytes(source)))
        eac3Source = clips.resolve("eac3.mkv")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24000/1001",
            "-f", "lavfi", "-i", "aevalsrc=$BEEP|$BEEP|0|0|0|0:s=48000:c=5.1",
            "-t", "30", "-c:v", "libx264", "-g", "96", "-keyint_min", "48", "-sc_threshold", "40", "-bf", "3",
            "-c:a", "eac3", "-b:a", "384k", eac3Source.toString(),
        )
        eac3Index = assertNotNull(MatroskaIndex.read(MatroskaIndexTest.FileBytes(eac3Source)))
        hevcSource = clips.resolve("hevc.mkv")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24000/1001",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "20", "-c:v", "libx265", "-x265-params", "log-level=error:keyint=96:min-keyint=48:bframes=3",
            "-c:a", "aac", "-ac", "2", hevcSource.toString(),
        )
        hevcIndex = assertNotNull(MatroskaIndex.read(MatroskaIndexTest.FileBytes(hevcSource)))
    }

    private fun ffmpeg(vararg arguments: String) {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *arguments)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg: $output" }
    }

    @AfterEach
    fun closeStreams() {
        streams.forEach(HlsStream::close)
        streams.clear()
        if (::scope.isInitialized) scope.cancel()
    }

    @AfterAll
    fun deleteClips() {
        clips.toFile().deleteRecursively()
    }

    /** [file]'s stream, read from [sourceUri] (the file itself unless it's served). */
    private fun stream(
        ahead: Int = 10,
        converted: Boolean = false,
        file: Path = if (converted) eac3Source else source,
        sourceUri: String = file.toString(),
        keepBehind: Int = 30,
        waitMillis: Long = 120_000,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): HlsStream {
        work = Files.createTempDirectory(clips, "session")
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fileIndex = mapOf(source to index, eac3Source to eac3Index, hevcSource to hevcIndex).getValue(file)
        return HlsStream(
            "ffmpeg", sourceUri, fileIndex, audio = AudioChoice(stream = 0, convert = converted), dir = work,
            scope = scope, hevc = file == hevcSource, aheadSegments = ahead, keepBehind = keepBehind,
            waitTimeoutMillis = waitMillis,
        ).also { streams += it }
    }

    private fun ready(file: HlsFile): Path = assertIs<HlsFile.Ready>(file).path

    /** [stream]'s segments asked for in [order], joined as the browser appends them: init, then each in turn. */
    private suspend fun joined(
        stream: HlsStream,
        order: List<Int> = (0 until stream.segmentCount).toList(),
        ask: suspend (Int) -> HlsFile = { stream.segment(it) },
    ): Path {
        val bytes = order.associateWith { ready(ask(it)).readBytes() }
        val init = ready(stream.init()).readBytes()
        val joined = clips.resolve("joined-${System.nanoTime()}.mp4")
        Files.write(joined, (0 until stream.segmentCount).fold(init) { all, segment -> all + bytes.getValue(segment) })
        return joined
    }

    /** Segment [number], asked for again while it's not made yet, as hls.js does on a 503. */
    private suspend fun askingAgain(stream: HlsStream, number: Int): HlsFile {
        repeat(3) { stream.segment(number).let { if (it !is HlsFile.NotYet) return it } }
        return stream.segment(number)
    }

    /** Segments from the middle to the end, then from the start: two FFmpeg runs, meeting in the middle. */
    private fun seekingOrder(stream: HlsStream): List<Int> {
        val middle = stream.segmentCount / 2
        return (middle until stream.segmentCount) + (0 until middle)
    }

    @Test
    fun `the playlist lists a segment per stretch between keyframes about 6 s apart`() {
        val stream = stream()
        val playlist = stream.playlist()
        val durations = Regex("""#EXTINF:([0-9.]+),""").findAll(playlist).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(stream.segmentCount, durations.size)
        assertTrue(
            durations.dropLast(1).all { it >= 6.0 - 0.001 },
            "every segment but the last at least 6 s: $durations",
        )
        assertTrue(kotlin.math.abs(durations.sum() - index.durationSeconds) < 0.01, "the segments add up to the whole")
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `segments asked for in order join into the source, packet for packet`() = runBlocking<Unit> {
        assertSamePackets(joined(stream(ahead = 3)))
    }

    @Test
    fun `a seek ahead restarts FFmpeg there, and the segments still join exactly`() = runBlocking<Unit> {
        val stream = stream(ahead = 2)
        assertSamePackets(joined(stream, seekingOrder(stream)))
    }

    @Test
    fun `audio stored before the start is left out, so no fragment's audio starts before 0`() = runBlocking<Unit> {
        // FFmpeg's AAC starts a frame before 0. An MP4 fragment's time can't be negative: hls.js reads it as 2^64 less
        // a frame, and puts the audio there.
        assertTrue(packets(source, "a").first().first < 0, "the clip has audio before 0")
        assertTrue(packets(joined(stream()), "a").first().first >= 0)
    }

    @Test
    fun `converted audio is AAC stereo, a frame every 1024 samples from 0, across FFmpeg runs`() = runBlocking<Unit> {
        val stream = stream(ahead = 2, converted = true)
        val joined = joined(stream, seekingOrder(stream))
        val format = ffprobe(joined, "a", "stream=codec_name,channels,time_base").single().split(',').take(3)
        assertEquals(listOf("aac", "2", "1/48000"), format)
        val times = ffprobe(joined, "a", "packet=pts").map(String::toLong)
        assertEquals(0L, times.first(), "the encoder's first frame, at 0")
        val uneven = times.zipWithNext { a, b -> b - a }.withIndex().filter { it.value != AAC_FRAME }
        assertTrue(uneven.isEmpty(), "frames not 1024 samples apart, by index: $uneven")
        assertTrue(times.last() + AAC_FRAME >= (eac3Index.durationSeconds - 0.05) * 48_000, "it lasts to the end")
    }

    @Test
    fun `converted audio keeps time with the video, each beep where the source has it`() = runBlocking<Unit> {
        val stream = stream(ahead = 2, converted = true)
        val joined = joined(stream, seekingOrder(stream))
        val want = beeps(eac3Source)
        val got = beeps(joined)
        assertEquals(want.size, got.size, "beeps: $got, not $want")
        want.zip(got).forEach { (w, g) -> assertTrue(kotlin.math.abs(w - g) < 0.002, "a beep at $g, not $w") }
    }

    @Test
    fun `FFmpeg stops a little ahead of the player`() = runBlocking<Unit> {
        val stream = stream(ahead = 2)
        ready(stream.segment(0))
        delay(3_000)
        val made = work.listDirectoryEntries("seg*.m4s").map { it.name }
        assertTrue(made.size <= 3, "made only the segment asked for and two ahead: $made")
    }

    @Test
    fun `closing stops FFmpeg and waits for it, so nothing of it runs or writes after`() = runBlocking<Unit> {
        val thread = Executors.newSingleThreadExecutor()
        ClipServer(source, bytesPerSecond = 200_000).use { server ->
            val stream = stream(ahead = 30, sourceUri = server.uri, dispatcher = thread.asCoroutineDispatcher())
            ready(stream.segment(0))
            assertTrue(stream.running, "FFmpeg is making the segments after it")
            // The thread that takes FFmpeg's segments, busy a moment as a loaded server's are: the close waits for it.
            thread.execute { Thread.sleep(500) }
            stream.close()
            val left = files(work)
            assertTrue(left.none { it.startsWith("run-") }, "the run's own folder gone: $left")
            assertEquals(0L, ffmpegProcesses(), "no ffmpeg left running")
            delay(1_000)
            assertEquals(left, files(work), "nothing written or deleted since")
        }
        thread.shutdownNow()
    }

    @Test
    fun `a seek back to a segment the running FFmpeg made and deleted makes it again`() = runBlocking<Unit> {
        ClipServer(source, bytesPerSecond = 500_000).use { server ->
            val stream = stream(ahead = 30, sourceUri = server.uri, keepBehind = 1, waitMillis = 60_000)
            // Asked for 3, the segments before 2 are deleted.
            for (number in 0..3) ready(stream.segment(number))
            assertTrue(stream.running, "the run is making the segments after 3")
            ready(withTimeout(15_000) { stream.segment(0) })
        }
    }

    @Test
    fun `a request waiting on a segment is answered at once when a seek elsewhere replaces its run`() =
        runBlocking<Unit> {
            ClipServer(source, bytesPerSecond = 100_000).use { server ->
                val stream = stream(ahead = 2, sourceUri = server.uri, waitMillis = 60_000)
                // At this pace, segment 3 takes seconds; the last is too far past it for its run to make soon.
                val waiting = async(Dispatchers.IO) { stream.segment(3) }
                delay(500)
                val seek = async(Dispatchers.IO) { stream.segment(stream.segmentCount - 1) }
                assertEquals(HlsFile.NotYet, withTimeout(5_000) { waiting.await() })
                seek.cancel()
            }
        }

    @Test
    fun `a segment a broken read cut short isn't kept, and once the read works it's made whole`() = runBlocking<Unit> {
        ClipServer(source, cutAt = Files.size(source) / 2).use { server ->
            val stream = stream(ahead = 30, sourceUri = server.uri)
            // Asked for in order, each segment up to the one the read breaks off in.
            var made = 0
            while (made < stream.segmentCount && stream.segment(made) is HlsFile.Ready) made++
            assertTrue(made in 1 until stream.segmentCount - 1, "made $made of ${stream.segmentCount}")
            assertEquals(HlsFile.NotYet, stream.segment(made), "asked for again while the read still breaks off")
            server.mend()
            assertSamePackets(joined(stream) { askingAgain(stream, it) })
        }
    }

    @Test
    fun `copied HEVC is tagged hvc1, as Apple's players want it, and H264 keeps avc1`() = runBlocking<Unit> {
        val hevc = joined(stream(file = hevcSource))
        assertEquals(listOf("hevc,hvc1"), ffprobe(hevc, "v", "stream=codec_name,codec_tag_string"))
        val h264 = joined(stream(ahead = 30))
        assertEquals(listOf("h264,avc1"), ffprobe(h264, "v", "stream=codec_name,codec_tag_string"))
    }

    @Test
    fun `a player asking in order gets segments from a few runs, not one run per segment`() = runBlocking<Unit> {
        val stream = stream(ahead = 4)
        for (number in 0 until stream.segmentCount) {
            ready(stream.segment(number))
            // As a player buffering ahead does, between asks: FFmpeg gets as far ahead as it may, and stops.
            withTimeout(30_000) { while (stream.running) delay(20) }
        }
        assertTrue(
            stream.runsStarted <= 1 + (stream.segmentCount - 4) / 2,
            "${stream.runsStarted} runs for ${stream.segmentCount} segments",
        )
    }

    private fun ffmpegProcesses(): Long =
        ProcessHandle.current().descendants().filter { it.info().command().orElse("").endsWith("ffmpeg") }.count()

    /** Every file and folder under [dir], by its path there. */
    private fun files(dir: Path): Set<String> =
        Files.walk(dir).use { paths -> paths.map { it.relativeTo(dir).toString() }.toList().toSet() }

    /**
     * [joined] segments against the source: the same packets, in order, at the same times; but for audio stored before
     * 0, which is left out.
     */
    private fun assertSamePackets(joined: Path) {
        for (stream in listOf("v", "a")) {
            val want = packets(source, stream).filter { it.first >= 0 }
            val got = packets(joined, stream)
            assertEquals(want.size, got.size, "$stream packets")
            assertEquals(want.map { it.second }, got.map { it.second }, "$stream packet sizes, in order")
            // ffprobe shows a fragmented MP4 with negative composition offsets shifted by the decode delay:
            // compare times relative to the first.
            val wantTimes = want.map { it.first - want.first().first }
            val gotTimes = got.map { it.first - got.first().first }
            wantTimes.zip(gotTimes).forEachIndexed { i, (w, g) ->
                assertTrue(kotlin.math.abs(w - g) < 0.002, "$stream packet $i at $g, not $w")
            }
        }
    }

    /** (pts, size) of each packet of [file]'s first [stream] ("v" or "a"), in file order. */
    private fun packets(file: Path, stream: String): List<Pair<Double, Int>> =
        ffprobe(file, stream, "packet=pts_time,size").map { line ->
            line.split(',').let { it[0].toDouble() to it[1].toInt() }
        }

    /** ffprobe's [entries] of [file]'s first [stream] ("v" or "a"), a line each. */
    private fun ffprobe(file: Path, stream: String, entries: String): List<String> {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "${stream}:0", "-show_entries", entries,
            "-of", "csv=p=0", file.toString(),
        ).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(60, TimeUnit.SECONDS)
        return lines.filter { it.isNotBlank() }
    }

    /**
     * When [file]'s beeps start, in seconds on its own clock, as FFmpeg finds silence end: but the first (at 0, with no
     * silence before it to end) and a last "end" that is the end of the file.
     */
    private fun beeps(file: Path): List<Double> {
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-nostats", "-copyts", "-i", file.toString(), "-map", "0:a",
            "-af", "silencedetect=n=-40dB:d=0.3", "-f", "null", "-",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return Regex("""silence_end: ([0-9.]+)""").findAll(output).map { it.groupValues[1].toDouble() }
            .filter { it >= 0.5 && it < eac3Index.durationSeconds - 0.5 }.toList()
    }

    /**
     * [file] over HTTP, by range, as debridav serves a release to FFmpeg: at [bytesPerSecond] if given, as a release
     * read from Usenet comes; and if [cutAt] is, every response that reaches that byte broken off there until [mend],
     * as a read of a missing article is. (Broken off once, FFmpeg reads that part again itself.)
     */
    private class ClipServer(
        private val file: Path,
        private val bytesPerSecond: Long? = null,
        cutAt: Long? = null,
    ) : AutoCloseable {
        private val cut = AtomicReference(cutAt)
        private val threads: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = threads
            createContext("/") { exchange -> exchange.use { runCatching { serve(it) } } }
            start()
        }

        val uri: String get() = "http://127.0.0.1:${server.address.port}/release.mkv"

        private fun serve(exchange: HttpExchange) {
            val size = Files.size(file)
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
            val from = range?.get(0)?.toLongOrNull() ?: 0L
            val to = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $from-$to/$size")
            exchange.sendResponseHeaders(if (range != null) 206 else 200, to - from + 1)
            // Fewer bytes than the length said: the connection is closed, and FFmpeg finds the read ended early.
            val end = cut.get()?.takeIf { it in from..to } ?: (to + 1)
            RandomAccessFile(file.toFile(), "r").use { input ->
                input.seek(from)
                val buffer = ByteArray(CHUNK)
                var at = from
                while (at < end) {
                    val read = input.read(buffer, 0, minOf(CHUNK.toLong(), end - at).toInt())
                    if (read < 0) break
                    try {
                        exchange.responseBody.write(buffer, 0, read)
                    } catch (_: IOException) {
                        return // FFmpeg stopped reading: it was stopped, or seeks.
                    }
                    at += read
                    bytesPerSecond?.let { Thread.sleep(read * 1000L / it) }
                }
            }
        }

        fun mend() = cut.set(null)

        override fun close() {
            server.stop(0)
            threads.shutdownNow()
        }

        private companion object {
            const val CHUNK = 16 * 1024
        }
    }

    private companion object {
        const val AAC_FRAME = 1024L
        /** 10 ms of 1 kHz at the start of each second, silence the rest. */
        const val BEEP = """if(lt(mod(t\,1)\,0.01)\,sin(2*PI*1000*t)\,0)"""
    }
}
