package io.skjaere.debridav.collection.hls

import io.ktor.utils.io.readAvailable
import io.skjaere.debridav.collection.play.OpenRelease
import io.skjaere.nzbstreamer.NzbStreamer
import java.io.ByteArrayOutputStream

/** An opened release, read by range from what nzb-streamer prepared in memory. */
class ReleaseBytes(private val nzbStreamer: NzbStreamer, private val release: OpenRelease) : ByteSource {
    override val size: Long = release.file.totalSize

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val end = minOf(offset + length, size) - 1
        if (offset > end) return ByteArray(0)
        val bytes = ByteArrayOutputStream((end - offset + 1).toInt())
        nzbStreamer.streamFile(release.metadata, release.file, offset..end) { channel ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                bytes.write(buffer, 0, read)
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
