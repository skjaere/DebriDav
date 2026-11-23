package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.arrs.RadarrConfigurationProperties
import io.skjaere.debridav.arrs.SonarrConfigurationProperties
import io.skjaere.debridav.collection.library.EpisodeDto
import io.skjaere.debridav.collection.library.MovieDto
import io.skjaere.debridav.collection.library.SeriesDto
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList
import java.time.LocalDate

/** The collection pages' content: movies from Radarr, series and their episodes from Sonarr, and their posters. */
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
class CollectionLibraryIT {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var mockServer: ClientAndServer

    @Autowired
    lateinit var sonarr: SonarrConfigurationProperties

    @Autowired
    lateinit var radarr: RadarrConfigurationProperties

    private var sonarrEnabled = true
    private var radarrEnabled = true

    @BeforeEach
    fun remember() {
        sonarrEnabled = sonarr.integrationEnabled
        radarrEnabled = radarr.integrationEnabled
    }

    @AfterEach
    fun tearDown() {
        sonarr.integrationEnabled = sonarrEnabled
        radarr.integrationEnabled = radarrEnabled
        mockServer.reset()
    }

    @Test
    fun `movies come from Radarr, by title, with what a search needs`() {
        json("/radarr/api/v3/movie", RADARR_MOVIES)

        val movies = webTestClient.get().uri("/api/v1/collection/movies").exchange()
            .expectStatus().isOk.expectBodyList<MovieDto>().returnResult().responseBody!!

        assertThat(movies.map { it.title }, equalTo(listOf("Harbor Lights", "The Long Harbor")))
        val harbor = movies.first()
        assertThat(harbor.id, equalTo(2L))
        assertThat(harbor.year, equalTo(2024))
        assertThat(harbor.imdbId, equalTo("tt1234567"))
        assertThat(harbor.tmdbId, equalTo(555001))
        assertThat(harbor.runtimeMinutes, equalTo(112))
        assertThat(harbor.originalLanguage, equalTo("de"))
        assertThat(harbor.poster, equalTo("/api/v1/collection/images/movie/2/poster"))
        assertThat("no poster in Radarr", movies.last().poster, equalTo(null))
        mockServer.verify(
            request().withPath("/radarr/api/v3/movie").withHeader("X-Api-Key", radarr.apiKey),
        )
    }

    @Test
    fun `series come from Sonarr with their seasons, and episodes by season and number`() {
        json("/sonarr/api/v3/series", SONARR_SERIES)
        mockServer.`when`(request().withPath("/sonarr/api/v3/episode").withQueryStringParameter("seriesId", "7"))
            .respond(
                response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(SONARR_EPISODES),
            )

        val series = webTestClient.get().uri("/api/v1/collection/series").exchange()
            .expectStatus().isOk.expectBodyList<SeriesDto>().returnResult().responseBody!!.single()
        assertThat(series.title, equalTo("The Lanternkeepers"))
        assertThat(series.tvdbId, equalTo(81189))
        assertThat(series.tvMazeId, equalTo(169))
        assertThat(series.originalLanguage, equalTo("en"))
        assertThat(series.seasons.map { it.number to it.episodes }, equalTo(listOf(0 to 1, 1 to 2)))
        assertThat(series.poster, equalTo("/api/v1/collection/images/series/7/poster"))

        val episodes = webTestClient.get().uri("/api/v1/collection/series/7/episodes").exchange()
            .expectStatus().isOk.expectBodyList<EpisodeDto>().returnResult().responseBody!!
        assertThat(episodes.map { it.season to it.episode }, equalTo(listOf(0 to 1, 1 to 1, 1 to 2)))
        val pilot = episodes[1]
        assertThat(pilot.title, equalTo("Pilot"))
        assertThat(pilot.airDate, equalTo(LocalDate.of(2008, 1, 20)))
        assertThat(pilot.runtimeMinutes, equalTo(58))
        assertThat(pilot.hasFile, equalTo(true))
    }

    @Test
    fun `a poster comes through debridav with the arr's key`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        mockServer.`when`(
            request().withPath("/radarr/api/v3/mediacover/2/poster.jpg").withHeader("X-Api-Key", radarr.apiKey),
        )
            .respond(response().withStatusCode(200).withContentType(MediaType.JPEG).withBody(jpeg))

        val body = webTestClient.get().uri("/api/v1/collection/images/movie/2/poster").exchange()
            .expectStatus().isOk
            .expectHeader().contentType("image/jpeg")
            .expectHeader().valueMatches("Cache-Control", ".*max-age=.*")
            .expectBody<ByteArray>().returnResult().responseBody!!
        assertThat(body.toList(), equalTo(jpeg.toList()))

        webTestClient.get().uri("/api/v1/collection/images/movie/2/banner").exchange().expectStatus().isBadRequest
    }

    @Test
    fun `with an arr turned off, its part of the collection is empty`() {
        radarr.integrationEnabled = false
        sonarr.integrationEnabled = false
        webTestClient.get().uri("/api/v1/collection/movies").exchange()
            .expectStatus().isOk.expectBodyList<MovieDto>().hasSize(0)
        webTestClient.get().uri("/api/v1/collection/series").exchange()
            .expectStatus().isOk.expectBodyList<SeriesDto>().hasSize(0)
        mockServer.verifyZeroInteractions()
    }

    @Test
    fun `an arr that fails is a bad gateway, not an empty collection`() {
        mockServer.`when`(request().withPath("/radarr/api/v3/movie")).respond(response().withStatusCode(500))
        webTestClient.get().uri("/api/v1/collection/movies").exchange().expectStatus().isEqualTo(502)
    }

    private fun json(path: String, body: String) {
        mockServer.`when`(request().withPath(path))
            .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON).withBody(body))
    }

    private companion object {
        val RADARR_MOVIES = """
            [
              {"id": 1, "title": "The Long Harbor", "year": 1998, "imdbId": "tt0133093", "tmdbId": 603, "runtime": 136,
               "overview": "A dockworker finds a map.", "hasFile": false,
               "originalLanguage": {"id": 1, "name": "English"},
               "images": []},
              {"id": 2, "title": "Harbor Lights", "year": 2024, "imdbId": "tt1234567", "tmdbId": 555001, "runtime": 112,
               "overview": "Lights over the water.", "hasFile": true, "originalLanguage": {"id": 4, "name": "German"},
               "images": [{"coverType": "poster", "url": "/MediaCover/2/poster.jpg?lastWrite=1"}], "unknownField": 1}
            ]
        """.trimIndent()

        val SONARR_SERIES = """
            [
              {"id": 7, "title": "The Lanternkeepers", "year": 2008,
               "tvdbId": 81189, "tmdbId": 1396, "imdbId": "tt0903747",
               "tvMazeId": 169, "runtime": 58, "overview": "Keepers of the light.",
               "originalLanguage": {"id": 1, "name": "English"},
               "images": [{"coverType": "poster", "url": "/MediaCover/7/poster.jpg?lastWrite=1"}],
               "seasons": [
                 {"seasonNumber": 0, "monitored": false, "statistics": {"totalEpisodeCount": 1, "episodeFileCount": 0}},
                 {"seasonNumber": 1, "monitored": true, "statistics": {"totalEpisodeCount": 2, "episodeFileCount": 1}}
               ]}
            ]
        """.trimIndent()

        val SONARR_EPISODES = """
            [
              {"id": 102, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 2,
               "title": "Second Light", "airDate": "2008-01-27",
               "runtime": 47, "hasFile": false, "overview": "Another night."},
              {"id": 100, "seriesId": 7, "seasonNumber": 0, "episodeNumber": 1,
               "title": "Behind the Light", "hasFile": false},
              {"id": 101, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 1,
               "title": "Pilot", "airDate": "2008-01-20",
               "runtime": 58, "hasFile": true, "overview": "The first night."}
            ]
        """.trimIndent()
    }
}
