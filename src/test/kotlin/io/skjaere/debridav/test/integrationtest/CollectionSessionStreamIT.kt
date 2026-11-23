package io.skjaere.debridav.test.integrationtest

import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.collection.play.OpenRelease
import io.skjaere.debridav.collection.play.PlaySessionRegistry
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.metadata.PrepareResult
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

/**
 * The release a play opened is streamed from memory (what nzb-streamer prepared), by range, to FFmpeg and ffprobe: only
 * through its session's token, and only while the session is open.
 */
@SpringBootTest(
    classes = [
        DebriDavApplication::class,
        IntegrationTestContextConfiguration::class,
        MiltonConfiguration::class,
        TestSecurityConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews"],
)
@MockServerNntpTest
class CollectionSessionStreamIT {

    @Autowired
    lateinit var mockNntpServerContainer: MockNntpServerContainer

    @Autowired
    lateinit var nzbStreamer: NzbStreamer

    @Autowired
    lateinit var registry: PlaySessionRegistry

    @Autowired
    lateinit var webTestClient: WebTestClient

    @AfterEach
    fun tearDown() {
        registry.closeAll()
        runBlocking { mockNntpServerContainer.client.clearYencBodyExpectations() }
    }

    @Test
    fun `the opened release streams from memory by range while its session is open`() {
        //given
        val data = ByteArray(SIZE) { (it * 7 % 251).toByte() }
        val nzb = runBlocking {
            mockNntpServerContainer.client.prepareArchiveNzb(mapOf("movie.mkv" to data), ContainerType.RAR5)
        }
        val prepared = runBlocking { nzbStreamer.prepare(nzb.toByteArray()) } as PrepareResult.Success
        val file = nzbStreamer.resolveStreamableFiles(prepared.metadata).single()
        val session = registry.open("tester")
        session.open(OpenRelease("Some.Movie.2024", prepared.metadata, file))
        val source = "/api/v1/collection/sessions/${session.token}/source"

        //then: the whole file
        val whole = webTestClient.get().uri(source).exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("Accept-Ranges", "bytes")
            .expectHeader().contentLength(SIZE.toLong())
            .expectBody<ByteArray>().returnResult().responseBody!!
        assertThat(whole.contentEquals(data), equalTo(true))

        //then: a range, and one running past the end, which ends at the end
        assertThat(
            range(source, "bytes=1000-4999", "bytes 1000-4999/$SIZE").toList(),
            equalTo(data.copyOfRange(1000, 5000).toList()),
        )
        assertThat(
            range(source, "bytes=60000-70000", "bytes 60000-${SIZE - 1}/$SIZE").toList(),
            equalTo(data.copyOfRange(60000, SIZE).toList()),
        )
        assertThat(
            range(source, "bytes=-100", "bytes ${SIZE - 100}-${SIZE - 1}/$SIZE").toList(),
            equalTo(data.copyOfRange(SIZE - 100, SIZE).toList()),
        )

        //then: a range starting past the end can't be served
        webTestClient.get().uri(source).header("Range", "bytes=$SIZE-").exchange()
            .expectStatus().isEqualTo(416)
            .expectHeader().valueEquals("Content-Range", "bytes */$SIZE")

        //then: no session, no stream
        webTestClient.get().uri("/api/v1/collection/sessions/not-a-token/source").exchange().expectStatus().isNotFound
        registry.close(session.token)
        webTestClient.get().uri(source).exchange().expectStatus().isNotFound
    }

    private fun range(source: String, range: String, contentRange: String): ByteArray =
        webTestClient.get().uri(source).header("Range", range).exchange()
            .expectStatus().isEqualTo(206)
            .expectHeader().valueEquals("Content-Range", contentRange)
            .expectBody<ByteArray>().returnResult().responseBody!!

    private companion object {
        const val SIZE = 64 * 1024
    }
}
