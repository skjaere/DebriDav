package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.EasynewsStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.sabnzbd.model.HistorySlot
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullHistoryResponse
import kotlinx.serialization.json.Json
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
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
    private lateinit var usenetRepository: UsenetRepository

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var easynewsStubbingService: EasynewsStubbingService

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @LocalServerPort
    var randomServerPort: Int = 0

    private val deserializer = Json { ignoreUnknownKeys = true }

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
        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(parts.build())).exchange().expectStatus().is2xxSuccessful

        // then
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("releaseName")
                )
            )
        )

        assertThat(
            sardine.list("http://localhost:${randomServerPort}/downloads/releaseName"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("releaseName.mkv")
                )
            )
        )

        val historyParts = MultipartBodyBuilder()
        historyParts.part("mode", "history")
        historyParts.part("cat", "testcat")


        val history: SabnzbdFullHistoryResponse =
            webTestClient.post().uri("/api").body(BodyInserters.fromMultipartData(historyParts.build())).exchange()
                .expectStatus().is2xxSuccessful.expectBody().jsonPath("$.history.slots[0].storage")
                .isEqualTo("/data/downloads/releaseName").jsonPath("$.history.slots[0].status").isEqualTo("COMPLETED")
                .returnResult().let { deserializer.decodeFromString(it.responseBodyContent!!.decodeToString()) }

        val deleteRequestParts = MultipartBodyBuilder()
        deleteRequestParts.part("mode", "history")
        deleteRequestParts.part("name", "delete")
        deleteRequestParts.part("value", history.history.slots.first().nzoId)

        webTestClient.post().uri("/api").body(BodyInserters.fromMultipartData(deleteRequestParts.build())).exchange()
            .expectStatus().is2xxSuccessful

        webTestClient.post().uri("/api").body(BodyInserters.fromMultipartData(historyParts.build())).exchange()
            .expectStatus().is2xxSuccessful.expectBody().jsonPath("$.history.slots").isEmpty


        sardine.delete("http://localhost:${randomServerPort}/downloads/releaseName")

    }

    @Test
    fun `that config endpoint points to correct download location`() {
        val parts = MultipartBodyBuilder()
        parts.part("mode", "get_config")
        webTestClient.post().uri("/api").body(BodyInserters.fromMultipartData(parts.build())).exchange()
            .expectStatus().is2xxSuccessful.expectBody().jsonPath("$.config.misc.download_dir")
            .isEqualTo("/data/downloads").jsonPath("$.config.misc.complete_dir").isEqualTo("/data/downloads")
    }

    @Test
    fun `that adding an nzb multiple times works`() {
        // given
        val addNzbParts = MultipartBodyBuilder()
        addNzbParts.part("mode", "addfile")
        addNzbParts.part("cat", "testcat")
        addNzbParts.part("name", "hello".toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=releaseName.nzb")

        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()
        repeat(2) {
            webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(addNzbParts.build())).exchange().expectStatus().is2xxSuccessful
        }
        sardine.delete("http://localhost:${randomServerPort}/downloads/releaseName")
        usenetRepository.deleteAll()

    }

    @Test
    fun `that deleting usenet download works`() {
        // given
        val addNzbParts = MultipartBodyBuilder()
        addNzbParts.part("mode", "addfile")
        addNzbParts.part("cat", "testcat")
        addNzbParts.part("name", "hello".toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=releaseName.nzb")

        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(addNzbParts.build())).exchange().expectStatus().is2xxSuccessful

        val getHistoryParts = MultipartBodyBuilder()
        getHistoryParts.part("mode", "history")
        getHistoryParts.part("cat", "testcat")

        val preDeleteHistory = deserializer.decodeFromString(
            SabnzbdFullHistoryResponse.serializer(),
            webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(getHistoryParts.build())).exchange()
                .expectStatus().is2xxSuccessful.expectBody(String::class.java).returnResult().responseBody!!
        )


        assertThat(preDeleteHistory.history.slots, hasSize(1))

        val deleteHistoryParts = MultipartBodyBuilder()
        deleteHistoryParts.part("mode", "history")
        deleteHistoryParts.part("name", "delete")
        deleteHistoryParts.part("value", preDeleteHistory.history.slots.first().nzoId.toString())

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(deleteHistoryParts.build())).exchange().expectStatus().is2xxSuccessful

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(getHistoryParts.build())).exchange()
            .expectStatus().is2xxSuccessful
            .expectBody()
            .jsonPath("$.history.slots", hasSize<HistorySlot>(preDeleteHistory.history.slots.size - 1))

        sardine.delete("http://localhost:${randomServerPort}/downloads/releaseName")
        usenetRepository.deleteAll()
    }

    @Test
    @Suppress("LongMethod")
    fun `that history endpoint respects category`() {
        // given
        val addNzbParts = MultipartBodyBuilder()
        addNzbParts.part("mode", "addfile")
        addNzbParts.part("cat", "testcat")
        addNzbParts.part("name", "hello".toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=releaseName.nzb")

        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(addNzbParts.build())).exchange().expectStatus().is2xxSuccessful

        mockserverClient.reset()
        easynewsStubbingService.mockSecondIsCached()
        easynewsStubbingService.stubSecondWorkingLink()

        val secondAddNzbParts = MultipartBodyBuilder()
        secondAddNzbParts.part("mode", "addfile")
        secondAddNzbParts.part("cat", "testcat2")
        secondAddNzbParts.part("name", "hello".toByteArray(Charsets.UTF_8))
            .header("Content-Disposition", "form-data; name=name; filename=secondReleaseName.nzb")

        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromMultipartData(secondAddNzbParts.build())).exchange().expectStatus().is2xxSuccessful

        // when
        val getHistoryForTestCatParts = MultipartBodyBuilder()
        getHistoryForTestCatParts.part("mode", "history")
        getHistoryForTestCatParts.part("cat", "testcat")

        val historyForTestCat = deserializer.decodeFromString(
            SabnzbdFullHistoryResponse.serializer(),
            webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(getHistoryForTestCatParts.build())).exchange()
                .expectStatus().is2xxSuccessful.expectBody(String::class.java).returnResult().responseBody!!
        )

        val getHistoryForTestCat2Parts = MultipartBodyBuilder()
        getHistoryForTestCat2Parts.part("mode", "history")
        getHistoryForTestCat2Parts.part("cat", "testcat2")

        val historyForTestCat2 = deserializer.decodeFromString(
            SabnzbdFullHistoryResponse.serializer(),
            webTestClient.post().uri("/api").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(getHistoryForTestCat2Parts.build())).exchange()
                .expectStatus().is2xxSuccessful.expectBody(String::class.java).returnResult().responseBody!!
        )

        // then
        assertThat(
            historyForTestCat.history.slots, allOf(
                hasSize(1),
                hasItems(
                    hasProperty<HistorySlot>(
                        "category", `is`("testcat")
                    ),
                    hasProperty<HistorySlot>(
                        "name", `is`("releaseName"),
                    )
                )
            )
        )
        assertThat(
            historyForTestCat2.history.slots, allOf(
                hasSize(1),
                hasItems(
                    hasProperty<HistorySlot>(
                        "category", `is`("testcat2")
                    ),
                    hasProperty<HistorySlot>(
                        "name", `is`("secondReleaseName"),
                    )
                )
            )
        )

        // finally
        sardine.delete("http://localhost:${randomServerPort}/downloads/releaseName")
        sardine.delete("http://localhost:${randomServerPort}/downloads/secondReleaseName")
        usenetRepository.deleteAll()
    }
}
