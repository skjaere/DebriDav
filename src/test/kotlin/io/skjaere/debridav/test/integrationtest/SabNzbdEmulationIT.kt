package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.EasynewsStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdHistoryResponse
import kotlinx.serialization.json.Json
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters


@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews"]
)
@MockServerTest
class SabNzbdEmulationIT {
    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var easynewsStubbingService: EasynewsStubbingService

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val sardine = SardineFactory.begin()

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
    }

    @Test
    @Suppress("LongMethod")
    fun `Adding nzb that is cached produces remotely cached entity and correct history response`() {
        // given
        val parts = MultipartBodyBuilder()
        parts.part("mode", "addfile")
        parts.part("cat", "testcat")
        parts.part("name", "hello".toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=releaseName.nzb")

        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        // when
        webTestClient
            .post()
            .uri("/api")
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        // then
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/"),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("releaseName")
                )
            )
        )

        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/releaseName"),
            hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("releaseName.mkv")
                )
            )
        )

        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")


        val history: SabnzbdHistoryResponse = webTestClient
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots[0].storage").isEqualTo("/data/downloads/releaseName")
            .jsonPath("$.history.slots[0].status").isEqualTo("COMPLETED")
            .returnResult()
            .let { Json.decodeFromString(it.responseBodyContent!!.decodeToString()) }

        val deleteRequestParts = MultipartBodyBuilder()
        deleteRequestParts.part("mode", "queue")
        deleteRequestParts.part("name", "delete")
        deleteRequestParts.part("value", history.history.slots.first().nzoId)

        webTestClient
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(deleteRequestParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        webTestClient
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots").isEmpty


        sardine.delete("http://localhost:${randomServerPort}/downloads/releaseName")

    }

    @Test
    fun `that config endpoint points to correct download location`() {
        val parts = MultipartBodyBuilder()
        parts.part("mode", "get_config")
        webTestClient
            .post()
            .uri("/api")
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.config.misc.download_dir").isEqualTo("/data/downloads")
            .jsonPath("$.config.misc.complete_dir").isEqualTo("/data/downloads")
    }

    /*    @Test
        fun `that deleted download does not show up in history`() {
            val usenetDownload = UsenetDownload()
            usenetDownload.
            val parts = MultipartBodyBuilder()
            parts.part("mode", "get_config")
            webTestClient
                .post()
                .uri("/api")
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus().is2xxSuccessful
                .expectBody()
                .jsonPath("$.config.misc.download_dir").isEqualTo("/data/downloads")
                .jsonPath("$.config.misc.complete_dir").isEqualTo("/data/downloads")
        }*/
}
