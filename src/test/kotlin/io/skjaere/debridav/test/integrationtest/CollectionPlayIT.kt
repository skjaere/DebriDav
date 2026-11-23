package io.skjaere.debridav.test.integrationtest

import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.collection.indexer.NewznabIndexer
import io.skjaere.debridav.collection.indexer.NewznabIndexerRepository
import io.skjaere.debridav.collection.play.PlaySessionRegistry
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import io.skjaere.nzbstreamer.nzb.NzbParser
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Playing a movie from the collection, end to end but for the outside services: Radarr and a Newznab indexer on
 * MockServer, the releases' articles on the mock NNTP server, real (tiny) video files, real ffprobe. The best-ranked
 * release has only AC-3 audio, which a Chrome-like browser can't decode: it fails at "Checking audio and video", and
 * the next one plays.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CollectionPlayIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var mockServer: ClientAndServer

    @Autowired
    lateinit var mockNntpServerContainer: MockNntpServerContainer

    @Autowired
    lateinit var indexers: NewznabIndexerRepository

    @Autowired
    lateinit var registry: PlaySessionRegistry

    private val clips = Files.createTempDirectory("collection-play-clips")
    private val json = JsonMapper.builder().build()

    @BeforeAll
    fun ffmpeg() {
        assumeTrue(runCatching { ProcessBuilder("ffprobe", "-version").start().waitFor() == 0 }.getOrDefault(false))
    }

    @AfterEach
    fun tearDown() {
        registry.closeAll()
        indexers.deleteAll()
        mockServer.reset()
        runBlocking {
            mockNntpServerContainer.client.clearYencBodyExpectations()
            mockNntpServerContainer.client.clearStatExpectations()
        }
    }

    @Test
    fun `a movie plays from the first release whose audio and video the browser decodes`() {
        //given
        val ac3Only = nzbOf(clip("ac3", "-c:a", "ac3", "-ac", "6"), "Harbor.Lights.2024.1080p.WEB-DL.H.264-AAA.mkv")
        val aac = nzbOf(clip("aac", "-c:a", "aac", "-ac", "2"), "Harbor.Lights.2024.720p.WEB-DL.H.264-BBB.mkv")
        radarrHasMovie()
        indexerServes(mapOf("aaa" to ac3Only, "bbb" to aac))

        //when
        val token = startPlay("""{"movieId":5,"capabilities":$CHROME}""")
        val events = eventsUntilDone(token)

        //then: the search
        val types = events.map { it.event() }
        assertThat(types.first(), equalTo("search-stage"))
        assertThat(field(events.single { it.event() == "search-done" }, "playable"), equalTo("2"))

        //then: the 1080p release was tried first and its audio refused, then the 720p one played
        val attempts = events.filter { it.event() == "attempt" }
            .map { json.readTree(it.data()).get("release").get("title").asText() }
        assertThat(
            attempts,
            equalTo(listOf("Harbor.Lights.2024.1080p.WEB-DL.H.264-AAA", "Harbor.Lights.2024.720p.WEB-DL.H.264-BBB")),
        )
        val probeFailure = events.single { it.event() == "step" && field(it, "status") == "failed" }
        assertThat(field(probeFailure, "step"), equalTo("ProbeTracks"))
        assertThat(field(probeFailure, "failure"), containsString("AC-3 5.1"))
        val playing = json.readTree(events.last().data())
        assertThat(events.last().event(), equalTo("playing"))
        assertThat(playing.get("release").get("title").asText(), equalTo("Harbor.Lights.2024.720p.WEB-DL.H.264-BBB"))
        assertThat(playing.get("audio").get(0).get("label").asText(), equalTo("AAC stereo"))
        assertThat(playing.get("audio").get(0).get("playable").asBoolean(), equalTo(true))
        assertThat(playing.get("video").get("height").asInt(), equalTo(240))

        //then: closing the play ends its session and its progress stream, and leaves nothing behind
        val workDir = registry.find(token)!!.workDir
        webTestClient.delete().uri("/api/v1/collection/sessions/$token").exchange().expectStatus().isNoContent
        assertThat(registry.find(token), nullValue())
        assertThat("its folder", Files.exists(workDir), equalTo(false))
        val probes = ProcessHandle.current().descendants()
            .filter { it.info().command().orElse("").endsWith("ffprobe") }.count()
        assertThat("ffprobe processes left", probes, equalTo(0L))
        webTestClient.get().uri("/api/v1/collection/sessions/$token/events").exchange().expectStatus().isNotFound
    }

    @Test
    fun `an episode plays from a release found by the series' TVDB id`() {
        //given
        val episode = nzbOf(
            clip("episode", "-c:a", "aac", "-ac", "2"),
            "The.Lanternkeepers.S01E02.720p.WEB-DL.H.264-CCC.mkv",
        )
        sonarrHasEpisode()
        indexerServes(
            mapOf("ccc" to episode),
            search = "tvsearch",
            titles = mapOf("ccc" to "The.Lanternkeepers.S01E02.720p.WEB-DL.H.264-CCC"),
        )

        //when
        val token = startPlay("""{"seriesId":7,"season":1,"episode":2,"capabilities":$CHROME}""")
        val events = eventsUntilDone(token)

        //then: searched by the series' TVDB id, and the episode played
        val byId = events.filter { it.event() == "search-indexer" }.mapNotNull { field(it, "by") }
        assertThat(byId.toSet(), equalTo(setOf("TVDB id")))
        assertThat(events.last().event(), equalTo("playing"))
        val playing = json.readTree(events.last().data())
        assertThat(
            playing.get("release").get("title").asText(),
            equalTo("The.Lanternkeepers.S01E02.720p.WEB-DL.H.264-CCC"),
        )
        mockServer.verify(
            request().withPath("/newznab/api").withQueryStringParameter("t", "tvsearch")
                .withQueryStringParameter("tvdbid", "81189").withQueryStringParameter("ep", "2"),
        )
    }

    @Test
    fun `a play can't start without an indexer, or for a movie Radarr doesn't have`() {
        webTestClient.post().uri("/api/v1/collection/play").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"movieId":5}""").exchange()
            .expectStatus().isBadRequest
            .expectBody<String>().value { assertThat(it, containsString("Add a Newznab indexer")) }

        indexerServes(emptyMap())
        mockServer.`when`(request().withPath("/radarr/api/v3/movie/6")).respond(response().withStatusCode(404))
        webTestClient.post().uri("/api/v1/collection/play").contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"movieId":6}""").exchange()
            .expectStatus().isBadRequest
            .expectBody<String>().value { assertThat(it, containsString("Radarr has no movie 6")) }
    }

    private fun startPlay(body: String): String {
        val started = webTestClient.post().uri("/api/v1/collection/play").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body).exchange()
            .expectStatus().isCreated
            .expectBody<String>().returnResult().responseBody!!
        return json.readTree(started).get("token").asText()
    }

    /** The play's events until it plays, gives up or fails. */
    private fun eventsUntilDone(token: String): List<ServerSentEvent<String>> =
        webTestClient.mutate().responseTimeout(Duration.ofMinutes(2)).build()
            .get().uri("/api/v1/collection/sessions/$token/events").accept(MediaType.TEXT_EVENT_STREAM).exchange()
            .expectStatus().isOk
            .returnResult(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .responseBody
            .takeUntil { it.event() in setOf("playing", "exhausted", "error") }
            .collectList()
            .block(Duration.ofMinutes(2))!!

    private fun field(event: ServerSentEvent<String>, name: String): String? =
        json.readTree(event.data()).get(name)?.asText()

    private fun radarrHasMovie() {
        mockServer.`when`(request().withPath("/radarr/api/v3/movie/5")).respond(
            response().withStatusCode(200).withContentType(org.mockserver.model.MediaType.APPLICATION_JSON).withBody(
                """{"id": 5, "title": "Harbor Lights", "year": 2024, "imdbId": "tt1234567", "tmdbId": 555001,
                   "runtime": 112, "originalLanguage": {"name": "English"}}""",
            ),
        )
    }

    private fun sonarrHasEpisode() {
        val jsonType = org.mockserver.model.MediaType.APPLICATION_JSON
        mockServer.`when`(request().withPath("/sonarr/api/v3/series/7")).respond(
            response().withStatusCode(200).withContentType(jsonType).withBody(
                """{"id": 7, "title": "The Lanternkeepers", "year": 2008, "tvdbId": 81189, "runtime": 45,
                   "originalLanguage": {"name": "English"}}""",
            ),
        )
        val episodes = request().withPath("/sonarr/api/v3/episode").withQueryStringParameter("seriesId", "7")
        mockServer.`when`(episodes).respond(
            response().withStatusCode(200).withContentType(jsonType).withBody(
                """[{"id": 101, "seasonNumber": 1, "episodeNumber": 1, "airDate": "2008-01-20"},
                    {"id": 102, "seasonNumber": 1, "episodeNumber": 2, "airDate": "2008-01-27"}]""",
            ),
        )
    }

    /** A Newznab indexer on MockServer whose movie search lists [nzbs]' releases, by guid, and serves their NZBs. */
    private fun indexerServes(
        nzbs: Map<String, ByteArray>,
        search: String = "movie",
        titles: Map<String, String> = mapOf(
            "aaa" to "Harbor.Lights.2024.1080p.WEB-DL.H.264-AAA",
            "bbb" to "Harbor.Lights.2024.720p.WEB-DL.H.264-BBB",
        ),
    ) {
        indexers.save(NewznabIndexer().apply {
            name = "Mock"
            url = "http://localhost:${mockServer.localPort}/newznab"
            apiKey = "KEY"
            createdAt = Instant.now()
            updatedAt = Instant.now()
        })
        val xml = org.mockserver.model.MediaType.APPLICATION_XML
        mockServer.`when`(request().withPath("/newznab/api").withQueryStringParameter("t", "caps"))
            .respond(response().withStatusCode(200).withContentType(xml).withBody(CAPS))
        val items = nzbs.keys.joinToString("") { guid ->
            val title = titles.getValue(guid)
            """<item><title>$title</title><guid>$guid</guid>
               <newznab:attr name="size" value="${if (guid == "aaa") 4_000_000_000 else 2_000_000_000}"/>
               <newznab:attr name="imdb" value="1234567"/></item>"""
        }
        mockServer.`when`(request().withPath("/newznab/api").withQueryStringParameter("t", search))
            .respond(response().withStatusCode(200).withContentType(xml).withBody(searchFeed(items)))
        nzbs.forEach { (guid, nzb) ->
            val get = request().withPath("/newznab/api")
                .withQueryStringParameter("t", "get").withQueryStringParameter("id", guid)
            mockServer.`when`(get)
                .respond(response().withStatusCode(200).withHeader("Content-Type", "application/x-nzb").withBody(nzb))
        }
    }

    /** A 2-second 320x240 H.264 clip with the audio [codecs] say. */
    private fun clip(name: String, vararg codecs: String): ByteArray {
        val file = clips.resolve("$name.mkv")
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "2", "-c:v", "libx264", *codecs, file.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg: $output" }
        return Files.readAllBytes(file)
    }

    /** [video] as a release on the mock NNTP server, its articles all there. */
    private fun nzbOf(video: ByteArray, name: String): ByteArray = runBlocking {
        val nzb = mockNntpServerContainer.client
            .prepareArchiveNzb(mapOf(name to video), ContainerType.RAR5)
            .toByteArray()
        NzbParser.parse(nzb).files.flatMap { it.segments }
            .forEach { mockNntpServerContainer.client.addStatExpectation("<${it.articleId}>", true) }
        nzb
    }

    private companion object {
        const val CHROME = """{"displayHeight":1080,"video":["h264","vp9","av1"],"audio":["aac","mp3","opus","flac"]}"""

        val CAPS = """
            <caps>
              <server title="Mock"/>
              <searching>
                <search available="yes" supportedParams="q"/>
                <movie-search available="yes" supportedParams="q,imdbid"/>
                <tv-search available="yes" supportedParams="q,tvdbid,season,ep"/>
              </searching>
              <categories><category id="2000" name="Movies"/></categories>
            </caps>
        """.trimIndent()

        fun searchFeed(items: String) = """
            <rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/">
              <channel><newznab:response offset="0" total="2"/>$items</channel>
            </rss>
        """.trimIndent()
    }
}
