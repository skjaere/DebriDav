package io.skjaere.debridav.test.integrationtest

import com.github.sardine.DavResource
import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.category.CategoryRepository
import io.skjaere.debridav.repository.UsenetRepository
import io.skjaere.debridav.test.integrationtest.config.EasynewsStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.usenet.sabnzbd.model.HistorySlot
import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullHistoryResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasEntry
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

    @Autowired
    lateinit var categoryRepository: CategoryRepository

    @LocalServerPort
    var randomServerPort: Int = 0

    private val deserializer = Json { ignoreUnknownKeys = true }

    private val sardine = SardineFactory.begin()


    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
        // Reset DB-side state too, so each test starts clean. Existing tests
        // self-clean via the SabNzbd history-delete API, but the addurl tests
        // exercise paths that don't surface a deletable history slot the same
        // way; doing it here makes tests order-independent.
        usenetRepository.deleteAll()
    }

    @Test
    fun `mode=addurl with cached release routes through Easynews path without fetching URL`() {
        // given — Easynews-only setup means the addurl handler skips the URL
        // fetch entirely (cache lookup is keyed on release name, not NZB bytes).
        // We never provide a working URL because the test pod isn't allowed to
        // hit the network — if the implementation tried to fetch, this test
        // would fail.
        //
        // The Easynews stub's canned response carries releaseName="releaseName",
        // and the matching service rejects mismatches; so we point `nzbname` at
        // that and use a *different* URL-derivable name to prove `nzbname`
        // overrides the URL-derived name.
        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        // Sonarr/Radarr send addurl as application/x-www-form-urlencoded, not
        // multipart. We mirror that here so the request shape matches prod.
        val formData = org.springframework.util.LinkedMultiValueMap<String, String>().apply {
            add("mode", "addurl")
            add("cat", "testcat")
            // URL-derived name would be `would-be-derived` — the test asserts
            // it is NOT what ends up on disk, because nzbname wins.
            add("name", "https://fake-indexer.invalid/getnzb/would-be-derived.nzb")
            add("nzbname", "releaseName")
        }

        // when
        webTestClient.post().uri("/api")
            .body(BodyInserters.fromFormData(formData)).exchange()
            .expectStatus().is2xxSuccessful

        // then — the release surfaces under the `nzbname` value, not the
        // URL-derived `would-be-derived`.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/"),
            hasItem<DavResource>(hasProperty("displayName", `is`("releaseName"))),
        )
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/releaseName"),
            hasItem<DavResource>(hasProperty("displayName", `is`("releaseName.mkv"))),
        )

        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")
    }

    @Test
    fun `mode=addurl reads release name from NZB head meta title when nzbname is absent`() {
        // given — no `nzbname`. The handler must fetch the URL, parse the
        // `<head><meta type="title">…` from the NZB XML, and use that as the
        // release name. URL-derived names from real indexers are typically
        // garbage (opaque IDs + apikey query strings), so the title meta is
        // the right source.
        easynewsStubbingService.mockIsCached()
        easynewsStubbingService.stubWorkingLink()

        // Stub a fake indexer URL that returns a minimal NZB document with the
        // title meta we want surfaced as the release name. Include the DOCTYPE
        // declaration that real-world NZBs ship — without it the previous XXE
        // protection silently rejected the parse and we fell back to the
        // URL-derived name.
        val nzbXml = """<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE nzb PUBLIC "-//newzBin//DTD NZB 1.1//EN" "http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd">
            |<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">
            |  <head>
            |    <meta type="title">releaseName</meta>
            |    <meta type="category">TV &gt; HD</meta>
            |  </head>
            |</nzb>
            |""".trimMargin()
        mockserverClient.`when`(
            org.mockserver.model.HttpRequest.request().withMethod("GET").withPath("/test-indexer/getnzb")
        ).respond(
            org.mockserver.model.HttpResponse.response().withStatusCode(200)
                .withContentType(org.mockserver.model.MediaType.APPLICATION_XML)
                .withBody(nzbXml)
        )

        val nzbUrl = "http://localhost:${easynewsStubbingService.port}/test-indexer/getnzb" +
            "?id=opaque-junk-no-real-name&apikey=secret"
        val formData = org.springframework.util.LinkedMultiValueMap<String, String>().apply {
            add("mode", "addurl")
            add("cat", "testcat")
            add("name", nzbUrl)
        }

        // when
        webTestClient.post().uri("/api")
            .body(BodyInserters.fromFormData(formData)).exchange()
            .expectStatus().is2xxSuccessful

        // then — release name comes from the NZB title meta, not the URL.
        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/"),
            hasItem<DavResource>(hasProperty("displayName", `is`("releaseName"))),
        )

        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")
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
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/"), hasItem<DavResource>(
                hasProperty(
                    "displayName", `is`("releaseName")
                )
            )
        )

        assertThat(
            sardine.list("http://localhost:${randomServerPort}/webdav/downloads/releaseName"), hasItem<DavResource>(
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


        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")

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
        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")
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
            .expectStatus().is2xxSuccessful.expectBody()
            .jsonPath("$.history.slots.length()").isEqualTo(preDeleteHistory.history.slots.size - 1)

        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")
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
                hasSize(1), hasItems(
                    hasProperty<HistorySlot>(
                        "category", `is`("testcat")
                    ), hasProperty<HistorySlot>(
                        "name", `is`("releaseName"),
                    )
                )
            )
        )
        assertThat(
            historyForTestCat2.history.slots, allOf(
                hasSize(1), hasItems(
                    hasProperty<HistorySlot>(
                        "category", `is`("testcat2")
                    ), hasProperty<HistorySlot>(
                        "name", `is`("secondReleaseName"),
                    )
                )
            )
        )

        // finally
        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/releaseName")
        sardine.delete("http://localhost:${randomServerPort}/webdav/downloads/secondReleaseName")
        usenetRepository.deleteAll()
    }

    @Test
    fun `that fullStatus provides correct category information`() {
        // given
        val fullstatusParts = MultipartBodyBuilder()
        fullstatusParts.part("mode", "get_config")

        val fullstatus = deserializer.decodeFromString<JsonObject>(
            webTestClient.post().uri("/api").body(BodyInserters.fromMultipartData(fullstatusParts.build())).exchange()
                .expectStatus().is2xxSuccessful.expectBody(String::class.java).returnResult().responseBody!!
        )

        // when
        val categoriesFromResponse =
            fullstatus["config"]?.jsonObject?.toMutableMap()["categories"]?.jsonArray?.toMutableList()?.map {
                it.jsonObject.toMutableMap().mapValues { it.value.jsonPrimitive.content }
            }?.map { it.toMutableMap() }


        // then
        assertThat(
            categoriesFromResponse!!, allOf(
                hasSize(categoryRepository.findAll().toList().size),
                hasItems(
                    hasEntry(equalTo("name"), equalTo("test1")),
                    hasEntry(equalTo("name"), equalTo("test2")),
                )
            )
        )
    }
}
