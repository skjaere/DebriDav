/*
package io.skjaere.debridav.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.mockk.mockk
import io.skjaere.debridav.ThrottlingService
import io.skjaere.debridav.debrid.client.torbox.TorBoxConfiguration
import io.skjaere.debridav.debrid.client.torbox.TorBoxTorrentClient
import kotlin.test.assertEquals
import net.bramp.ffmpeg.FFprobe
import org.junit.jupiter.api.Test

class VideoMetaDataTest {
    private var numberOfReceivedRequests = 0;
    private val client = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                when (request.url.host) {
                    "test.com" -> {
                        numberOfReceivedRequests++
                        respond("too much!", HttpStatusCode.TooManyRequests, Headers.build {
                            append("x-ratelimit-after", "1")
                        })
                    }

                    else -> error("Unhandled ${request.url}")
                }
            }
        }
    }

    private val throttlingService = ThrottlingService()
    private val torBoxClient = TorBoxTorrentClient(client, mockk<TorBoxConfiguration>())
    private val ffprobe = mockk<FFprobe>()

    */
/*private val underTest = VideoFileMetaDataService(throttlingService, client, listOf(torBoxClient), ffprobe)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun that429ResponseIsRetried() {
        // given
        val cachedFile = mockk<CachedFile>()
        every { cachedFile.provider } returns DebridProvider.TORBOX
        every { cachedFile.link } returns "http://test.com"
        val testScope = TestScope()

        //when
        testScope.runTest {
            val response = underTest.getMetadataFromUrl(cachedFile.path)

            // then
            assert((response is VideMetaData.Error))
            assert(numberOfReceivedRequests == 6)
            assertEquals(5_000L, testScope.currentTime)
        }
    }*//*


    @Test
    fun test() {
        val path =
            "Tulsa.King.S01E04.Visitation.Place.2160p.AMZN.WEB-DL.DDP5.1.H.265-NTb.mkv"
        val newPath = replaceRootDirectoryOfFilePathWithReleaseName(
            path,
            "Tulsa.King.S01E04.Visitation.Place.2160p.AMZN.WEB-DL.DDP5.1.H.265-NTb"
        )
        assertEquals("hello", newPath)
    }

    private fun replaceRootDirectoryOfFilePathWithReleaseName(path: String, releaseName: String): String {
        val parts = path.split("/").toMutableList()
        if (parts.size == 1) {
            return "$releaseName/$path"
        }
        parts[0] = releaseName
        return parts.joinToString("/")
    }
}*/
