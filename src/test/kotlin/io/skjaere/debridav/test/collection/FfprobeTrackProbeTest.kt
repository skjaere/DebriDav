package io.skjaere.debridav.test.collection

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.skjaere.debridav.collection.play.BrowserCapabilities
import io.skjaere.debridav.collection.play.CollectionPlayProperties
import io.skjaere.debridav.collection.play.FfprobeTrackProbe
import io.skjaere.debridav.collection.play.PlaySessionRegistry
import io.skjaere.nzbstreamer.play.FileListing
import io.skjaere.nzbstreamer.play.ListedFile
import io.skjaere.nzbstreamer.play.PlayCandidate
import io.skjaere.nzbstreamer.play.PlaybackTarget
import io.skjaere.nzbstreamer.play.ProbeResult
import io.skjaere.nzbstreamer.play.UnreadableFile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The "Checking audio and video" step: ffprobe reads the opened file, and it plays only when the browser can decode its
 * video and at least one of its audio tracks once remuxed to MP4 (no transcoding).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FfprobeTrackProbeTest {

    // Made here, not with @TempDir: the clips are made once, in @BeforeAll, before JUnit fills in such fields.
    private val dir: Path = Files.createTempDirectory("ffprobe-clips")
    private val work: Path = Files.createTempDirectory("ffprobe-sessions")

    @AfterAll
    fun deleteFolders() {
        listOf(dir, work).forEach { it.toFile().deleteRecursively() }
    }

    private val chrome = BrowserCapabilities(
        displayHeight = 1080,
        video = listOf("h264", "vp9", "av1"),
        audio = listOf("aac", "mp3", "opus", "flac"),
    )
    private val safari = chrome.copy(
        video = listOf("h264", "hevc"),
        videoTenBit = listOf("hevc"),
        audio = listOf("aac", "ac3", "eac3"),
    )

    @BeforeAll
    fun clips() {
        assumeTrue(available("ffmpeg") && available("ffprobe"), "needs ffmpeg and ffprobe")
        clip("h264-aac.mkv", "-c:v", "libx264", "-c:a", "aac", "-ac", "2", "-metadata:s:a:0", "language=eng")
        clip("h264-ac3.mkv", "-c:v", "libx264", "-c:a", "ac3", "-ac", "6")
        clip(
            "hevc10-aac.mkv",
            "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-x265-params", "log-level=error", "-c:a", "aac",
        )
        dir.resolve("broken.mkv").writeBytes(ByteArray(64 * 1024) { (it * 31 % 256).toByte() })
    }

    @Test
    fun `H264 with AAC plays in Chrome, with its tracks`() = runBlocking<Unit> {
        val target = target("h264-aac.mkv")
        val result = probe(chrome, target)
        assertIs<ProbeResult.Playable>(result)
        assertTrue(target.kept, "the probe keeps what it reads")
        val tracks = result.tracks!!
        assertEquals(1, tracks.video.size)
        assertEquals(320, tracks.video.single().width)
        assertEquals("eng", tracks.audio.single().language)
        assertEquals(2, tracks.audio.single().channels)
    }

    @Test
    fun `AC-3 alone plays in Chrome too, converted, as it does in Safari`() = runBlocking<Unit> {
        assertIs<ProbeResult.Playable>(probe(chrome, target("h264-ac3.mkv")))
        assertIs<ProbeResult.Playable>(probe(safari, target("h264-ac3.mkv")))
    }

    @Test
    fun `10-bit HEVC is no video unless the browser decodes it`() = runBlocking<Unit> {
        val result = probe(chrome, target("hevc10-aac.mkv"))
        assertIs<ProbeResult.NoPlayableVideo>(result)
        assertEquals(listOf("HEVC 10-bit"), result.found)
        assertIs<ProbeResult.NoPlayableVideo>(probe(safari.copy(videoTenBit = emptyList()), target("hevc10-aac.mkv")))
        assertIs<ProbeResult.Playable>(probe(safari, target("hevc10-aac.mkv")))
    }

    @Test
    fun `a file ffprobe can't read fails as unreadable`() = runBlocking<Unit> {
        val result = probe(chrome, target("broken.mkv"))
        assertIs<ProbeResult.Failed>(result)
        assertEquals(UnreadableFile.UnknownFormat, result.unreadable)
    }

    private suspend fun probe(capabilities: BrowserCapabilities, target: PlaybackTarget): ProbeResult {
        val properties = CollectionPlayProperties().apply { workDir = work.toString() }
        val registry = PlaySessionRegistry(properties, Clock.systemUTC(), SimpleMeterRegistry())
        val session = registry.open("tester")
        try {
            return FfprobeTrackProbe("ffprobe", capabilities, session).probe(target)
        } finally {
            registry.closeAll()
        }
    }

    private fun clip(name: String, vararg codecs: String) {
        val command = listOf(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "2", *codecs, dir.resolve(name).toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed for $name: $output" }
    }

    private fun available(binary: String): Boolean = runCatching {
        ProcessBuilder(binary, "-version").redirectErrorStream(true).start().let { p ->
            p.inputStream.readAllBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }
    }.getOrDefault(false)

    private fun target(name: String) = FileTarget(dir.resolve(name).toString())

    /** A file on disk standing in for a release streamed from Usenet: ffprobe reads a path as it reads a URL. */
    class FileTarget(override val uri: String) : PlaybackTarget {
        var kept = false
        private val listed = object : ListedFile {
            override val index = 0
            override val path = uri
            override val size = 0L
        }
        override val candidate = PlayCandidate(id = uri, title = uri)
        override val listing = object : FileListing<ListedFile> {
            override val id = uri
            override val title = uri
            override val files = listOf(listed)
        }
        override val file: ListedFile = listed

        override suspend fun keep() {
            kept = true
        }
    }
}
