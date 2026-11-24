package io.skjaere.debridav.test.collection

import io.skjaere.debridav.collection.hls.ByteSource
import io.skjaere.debridav.collection.hls.MatroskaIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where an MKV's video keyframes are, read from its Cues with a few small reads, never the whole file: the times its
 * HLS segments are cut at.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatroskaIndexTest {

    private val dir: Path = Files.createTempDirectory("matroska-index")

    @BeforeAll
    fun clips() {
        assumeTrue(runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        // Keyframes every 2-4 s, B-frames, audio beside: as a release has them.
        clip("cues-at-end.mkv")
        clip("cues-at-front.mkv", "-cues_to_front", "1")
        clip("no-cues.mkv", "-live", "1")
    }

    @AfterAll
    fun deleteClips() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the keyframes are read from the cues after the clusters, as ffprobe finds them`() = runBlocking<Unit> {
        val source = FileBytes(dir.resolve("cues-at-end.mkv"))
        val index = assertNotNull(MatroskaIndex.read(source))
        val expected = ffprobeKeyframes("cues-at-end.mkv")
        assertEquals(expected.size, index.keyframes.size, "keyframes ${index.keyframes} vs $expected")
        expected.zip(index.keyframes).forEach { (want, got) -> assertTrue(abs(want - got) < 0.002, "$got vs $want") }
        assertTrue(abs(index.durationSeconds - 30.0) < 0.1, "duration ${index.durationSeconds}")
        assertTrue(source.bytesRead.get() < source.size / 4, "read ${source.bytesRead.get()} of ${source.size} bytes")
    }

    @Test
    fun `cues before the clusters are read too`() = runBlocking<Unit> {
        val index = assertNotNull(MatroskaIndex.read(FileBytes(dir.resolve("cues-at-front.mkv"))))
        assertEquals(ffprobeKeyframes("cues-at-front.mkv").size, index.keyframes.size)
    }

    @Test
    fun `a file without cues has no index`() = runBlocking<Unit> {
        assertNull(MatroskaIndex.read(FileBytes(dir.resolve("no-cues.mkv"))))
    }

    @Test
    fun `a file that isn't Matroska has no index`() = runBlocking<Unit> {
        val mp4 = dir.resolve("clip.mp4")
        Files.write(mp4, ByteArray(4096) { 7 })
        assertNull(MatroskaIndex.read(FileBytes(mp4)))
    }

    private fun clip(name: String, vararg options: String) {
        val command = listOf(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "30", "-c:v", "libx264", "-g", "96", "-keyint_min", "48", "-bf", "3", "-c:a", "aac",
            *options, dir.resolve(name).toString(),
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg: $output" }
    }

    private fun ffprobeKeyframes(name: String): List<Double> {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "packet=pts_time,flags",
            "-of", "csv=p=0", dir.resolve(name).toString(),
        ).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(60, TimeUnit.SECONDS)
        return lines.filter { it.contains(",K") }.map { it.substringBefore(',').toDouble() }
    }

    /** A file read by range, counting the bytes read. */
    class FileBytes(private val path: Path) : ByteSource {
        val bytesRead = AtomicLong()
        override val size: Long = Files.size(path)

        override suspend fun read(offset: Long, length: Int): ByteArray =
            RandomAccessFile(path.toFile(), "r").use { file ->
                val bytes = ByteArray(minOf(length.toLong(), size - offset).toInt().coerceAtLeast(0))
                file.seek(offset)
                file.readFully(bytes)
                bytesRead.addAndGet(bytes.size.toLong())
                bytes
            }
    }
}
