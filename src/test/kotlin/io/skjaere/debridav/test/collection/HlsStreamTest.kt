package io.skjaere.debridav.test.collection

import io.skjaere.debridav.collection.hls.HlsStream
import io.skjaere.debridav.collection.hls.MatroskaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A release as HLS: fragmented-MP4 segments cut at its keyframes by FFmpeg copying its video and audio (nothing
 * transcoded), made as the player asks for them. Joined in order, the segments must hold every packet of the source
 * exactly once, in order, whichever order they were asked for and however many FFmpeg runs made them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HlsStreamTest {

    private val clips: Path = Files.createTempDirectory("hls-clips")
    private lateinit var source: Path
    private lateinit var index: MatroskaIndex
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

    private fun stream(ahead: Int = 10): HlsStream {
        work = Files.createTempDirectory(clips, "session")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return HlsStream(
            "ffmpeg", source.toString(), index, audioStream = 0, dir = work, scope = scope, aheadSegments = ahead,
        ).also { streams += it }
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
        val stream = stream(ahead = 3)
        val segments = (0 until stream.segmentCount).map { assertNotNull(stream.segment(it)).readBytes() }
        val init = assertNotNull(stream.init()).readBytes()
        assertSamePackets(init, segments)
    }

    @Test
    fun `a seek ahead restarts FFmpeg there, and the segments still join exactly`() = runBlocking<Unit> {
        val stream = stream(ahead = 2)
        val middle = stream.segmentCount / 2
        val order = (middle until stream.segmentCount) + (0 until middle)
        val bytes = order.associateWith { assertNotNull(stream.segment(it)).readBytes() }
        val init = assertNotNull(stream.init()).readBytes()
        assertSamePackets(init, (0 until stream.segmentCount).map(bytes::getValue))
    }

    @Test
    fun `FFmpeg stops a little ahead of the player, and is gone once the stream closes`() = runBlocking<Unit> {
        val stream = stream(ahead = 2)
        assertNotNull(stream.segment(0))
        delay(3_000)
        val made = work.listDirectoryEntries("seg*.m4s").map { it.name }
        assertTrue(made.size <= 3, "made only the segment asked for and two ahead: $made")
        stream.close()
        assertEquals(0L, ffmpegProcesses(), "no ffmpeg left running")
    }

    private fun ffmpegProcesses(): Long =
        ProcessHandle.current().descendants().filter { it.info().command().orElse("").endsWith("ffmpeg") }.count()

    /**
     * init + segments, as the browser appends them, against the source: the same packets, in order, at the same times.
     */
    private fun assertSamePackets(init: ByteArray, segments: List<ByteArray>) {
        val joined = clips.resolve("joined-${System.nanoTime()}.mp4")
        Files.write(joined, init + segments.fold(ByteArray(0)) { all, segment -> all + segment })
        for (stream in listOf("v", "a")) {
            val want = packets(source, stream)
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
    private fun packets(file: Path, stream: String): List<Pair<Double, Int>> {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "${stream}:0", "-show_entries", "packet=pts_time,size",
            "-of", "csv=p=0", file.toString(),
        ).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(60, TimeUnit.SECONDS)
        return lines.filter { it.isNotBlank() }.map { line -> line.split(',').let { it[0].toDouble() to it[1].toInt() } }
    }
}
