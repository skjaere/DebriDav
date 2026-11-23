package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.collection.indexer.IndexerDto
import io.skjaere.debridav.collection.indexer.IndexerTestResultDto
import io.skjaere.debridav.collection.indexer.NewznabIndexerRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

/** The Newznab indexers the collection player searches: kept in the database, managed through the API. */
@SpringBootTest(
    classes = [
        DebriDavApplication::class,
        IntegrationTestContextConfiguration::class,
        MiltonConfiguration::class,
        TestSecurityConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"],
)
@MockServerTest
class NewznabIndexerIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var repository: NewznabIndexerRepository

    @Autowired
    lateinit var mockServer: ClientAndServer

    @AfterEach
    fun tearDown() {
        repository.deleteAll()
        mockServer.reset()
    }

    @Test
    fun `an indexer is saved and listed without its API key`() {
        val created = create("""{"name":"Geek","url":"https://api.geek.example","apiKey":"SECRET-KEY"}""")

        assertThat(created.name, equalTo("Geek"))
        assertThat(created.hasApiKey, equalTo(true))
        assertThat(created.urlSecurity, equalTo("Encrypted"))
        val listed = webTestClient.get().uri(BASE).exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertThat(listed, containsString("Geek"))
        assertThat("the key never leaves the server", listed, not(containsString("SECRET-KEY")))
        assertThat(repository.findAll().single().apiKey, equalTo("SECRET-KEY"))
    }

    @Test
    fun `an address over plain http to the internet is flagged`() {
        val created = create("""{"name":"Plain","url":"http://indexer.example.org","apiKey":"K"}""")
        assertThat(created.urlSecurity, equalTo("PublicHttp"))
    }

    @Test
    fun `an edit without a key keeps it only while the address is the same server`() {
        val id = create("""{"name":"Geek","url":"https://api.geek.example","apiKey":"SECRET-KEY"}""").id

        update(id, """{"name":"Geek renamed","url":"https://api.geek.example/api/","enabled":false}""")
            .expectStatus().isOk
        val kept = repository.findById(id).get()
        assertThat(kept.apiKey, equalTo("SECRET-KEY"))
        assertThat(kept.name, equalTo("Geek renamed"))
        assertThat(kept.enabled, equalTo(false))

        update(id, """{"name":"Geek","url":"https://elsewhere.example"}""").expectStatus().isBadRequest
        update(id, """{"name":"Geek","url":"http://api.geek.example"}""").expectStatus().isBadRequest
        val unchanged = repository.findById(id).get().url
        assertThat("refused edits change nothing", unchanged, equalTo("https://api.geek.example/api/"))

        update(id, """{"name":"Geek","url":"https://elsewhere.example","apiKey":"OTHER-KEY"}""").expectStatus().isOk
        assertThat(repository.findById(id).get().apiKey, equalTo("OTHER-KEY"))
    }

    @Test
    fun `an indexer that can't work is refused`() {
        listOf(
            """{"name":"","url":"https://a.example","apiKey":"K"}""",
            """{"name":"No address","url":"not a url","apiKey":"K"}""",
            """{"name":"Ftp","url":"ftp://a.example","apiKey":"K"}""",
            """{"name":"No key","url":"https://a.example"}""",
            """{"name":"Agent","url":"https://a.example","apiKey":"K","userAgent":"bad\u0007agent"}""",
        ).forEach { body ->
            webTestClient.post().uri(BASE).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isBadRequest
        }
        assertThat(repository.count(), equalTo(0L))
    }

    @Test
    fun `an indexer is deleted`() {
        val id = create("""{"name":"Geek","url":"https://api.geek.example","apiKey":"K"}""").id
        webTestClient.delete().uri("$BASE/$id").exchange().expectStatus().isNoContent
        assertThat(repository.count(), equalTo(0L))
        webTestClient.get().uri(BASE).exchange().expectBodyList<IndexerDto>().hasSize(0)
    }

    @Test
    fun `testing an indexer reads its caps, with the saved key when none is typed`() {
        mockServer.`when`(request().withPath("/api").withQueryStringParameter("t", "caps"))
            .respond(
                response().withStatusCode(200).withContentType(MOCK_XML).withBody(CAPS),
            )
        val url = "http://localhost:${mockServer.localPort}"
        val id = create("""{"name":"Local","url":"$url","apiKey":"SAVED-KEY"}""").id

        val result = webTestClient.post().uri("$BASE/test").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"id":$id,"url":"$url"}""").exchange()
            .expectStatus().isOk.expectBody<IndexerTestResultDto>().returnResult().responseBody!!

        assertThat(result.success, equalTo(true))
        assertThat(result.serverTitle, equalTo("Example Newznab"))
        assertThat(
            result.searches,
            equalTo(listOf("search: q", "tv-search: q, tvdbid, season, ep", "movie-search: q, imdbid")),
        )
        mockServer.verify(request().withPath("/api").withQueryStringParameter("apikey", "SAVED-KEY"))
    }

    @Test
    fun `a failing test says why, and never shows the key`() {
        mockServer.`when`(request().withPath("/api"))
            .respond(response().withStatusCode(200).withContentType(MOCK_XML)
                .withBody("""<error code="100" description="Incorrect user credentials"/>"""))
        val url = "http://localhost:${mockServer.localPort}"

        val result = webTestClient.post().uri("$BASE/test").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"url":"$url","apiKey":"TYPED-KEY"}""").exchange()
            .expectStatus().isOk.expectBody<IndexerTestResultDto>().returnResult().responseBody!!

        assertThat(result.success, equalTo(false))
        assertThat(result.message, containsString("Incorrect user credentials"))
        assertThat(result.message, not(containsString("TYPED-KEY")))
    }

    private fun create(body: String): IndexerDto =
        webTestClient.post().uri(BASE).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
            .expectStatus().isCreated.expectBody<IndexerDto>().returnResult().responseBody!!

    private fun update(id: Long, body: String) =
        webTestClient.put().uri("$BASE/$id").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()

    private companion object {
        const val BASE = "/api/v1/collection/indexers"

        val MOCK_XML: org.mockserver.model.MediaType = org.mockserver.model.MediaType.APPLICATION_XML

        val CAPS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <caps>
              <server version="0.8.0" title="Example Newznab"/>
              <limits max="100" default="100"/>
              <searching>
                <search available="yes" supportedParams="q"/>
                <tv-search available="yes" supportedParams="q,tvdbid,season,ep"/>
                <movie-search available="yes" supportedParams="q,imdbid"/>
                <audio-search available="no" supportedParams=""/>
              </searching>
              <categories><category id="5000" name="TV"/></categories>
            </caps>
        """.trimIndent()
    }
}
