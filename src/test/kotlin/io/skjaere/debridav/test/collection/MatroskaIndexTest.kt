package io.skjaere.debridav.test.collection

import io.skjaere.debridav.collection.hls.ByteSource
import io.skjaere.debridav.collection.hls.MatroskaIndex
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

    @Test
    fun `an element whose size runs past the file can't send the reader round in a loop`() {
        // An uploader controls a release's bytes. 2^32 bytes from its start, a Void element ends, in Int arithmetic,
        // where it starts.
        val head = EBML_HEADER + SEGMENT_OF_UNKNOWN_SIZE + sized(VOID, (1L shl 32) - SIZED_HEADER_BYTES)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertNull(runBlocking { MatroskaIndex.read(Bytes(head)) })
        }
    }

    @Test
    fun `a header whose size runs past the file is no index, not a crash`() {
        // Its end, 2^31 bytes on, is a negative Int.
        val head = sized(EBML, 1L shl 31) + SEGMENT_OF_UNKNOWN_SIZE
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertNull(runBlocking { MatroskaIndex.read(Bytes(head)) })
        }
    }

    @Test
    fun `a seek head pointing before the file is no index, not a crash`() = runBlocking<Unit> {
        // A Seek Head: the Cues are at the largest negative position.
        val seekId = byteArrayOf(0x53, 0xAB.toByte(), 0x84.toByte(), 0x1C, 0x53, 0xBB.toByte(), 0x6B)
        val seekPosition = byteArrayOf(0x53, 0xAC.toByte(), 0x88.toByte(), 0x80.toByte()) + ByteArray(7)
        val seek = byteArrayOf(0x4D, 0xBB.toByte(), (0x80 + seekId.size + seekPosition.size).toByte()) +
            seekId + seekPosition
        val seekHead = byteArrayOf(0x11, 0x4D, 0x9B.toByte(), 0x74, (0x80 + seek.size).toByte()) + seek
        assertNull(MatroskaIndex.read(Bytes(EBML_HEADER + SEGMENT_OF_UNKNOWN_SIZE + seekHead)))
    }

    /** [id], then [size] as an 8-byte size: the longest EBML allows, as a hostile file would use. */
    private fun sized(id: ByteArray, size: Long): ByteArray =
        id + byteArrayOf(1) + ByteArray(7) { (size ushr (8 * (6 - it))).toByte() }

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

    /** Bytes in memory, read by range; as a release, refusing a read from before its start. */
    class Bytes(private val bytes: ByteArray) : ByteSource {
        override val size: Long = bytes.size.toLong()

        override suspend fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0) { "read from $offset" }
            return bytes.copyOfRange(minOf(offset, size).toInt(), minOf(offset + length, size).toInt())
        }
    }

    private companion object {
        val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
        val VOID = byteArrayOf(0xEC.toByte())
        /** An EBML header with nothing in it. */
        val EBML_HEADER = EBML + byteArrayOf(0x80.toByte())
        /** A Segment whose size isn't known, as a file being written has it: its children are read until the end. */
        val SEGMENT_OF_UNKNOWN_SIZE =
            byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01) + ByteArray(7) { 0xFF.toByte() }
        /** A one-byte id and an 8-byte size. */
        const val SIZED_HEADER_BYTES = 9L
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
