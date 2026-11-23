package io.skjaere.debridav.test.integrationtest

import com.github.sardine.SardineFactory
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.resource.DebridFileResource
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpError.error
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * A stream or link check holds the copy of the file it loaded when the request started, for as
 * long as it runs. Writing its outcome back must not undo a move or rename the arr made meanwhile.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class StaleFileWriteIT {

    @Autowired
    lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var streamingService: StreamingService

    @Autowired
    lateinit var debridLinkService: DebridLinkService

    @Autowired
    lateinit var debridavConfigurationProperties: DebridavConfigurationProperties

    @Autowired
    lateinit var mockServer: ClientAndServer

    @Value("\${mockserver.port}")
    var mockServerPort: Int = 0

    @LocalServerPort
    var serverPort: Int = 0

    private val sardine = SardineFactory.begin()

    @AfterEach
    fun tearDown() {
        listOf("/downloads/stale-renamed", "/downloads/stale").forEach { path ->
            runCatching { sardine.delete("http://localhost:$serverPort/webdav$path") }
        }
        mockServer.reset()
    }

    @Test
    fun `a failed stream doesn't undo a move made while it ran`() {
        //given: a file whose link answers HEAD but drops the connection on GET
        mockServer.`when`(request().withMethod("HEAD").withPath("/staleLink"))
            .respond(response().withStatusCode(200))
        mockServer.`when`(request().withMethod("GET").withPath("/staleLink"))
            .error(error().withDropConnection(true))
        val created = databaseFileService.createDebridFile(
            "/downloads/stale/episode.mkv", "stalehash", contents("http://localhost:$mockServerPort/staleLink")
        )
        databaseFileService.saveDbEntity(created)
        val heldByTheStream = databaseFileService.getFileAtPath("/downloads/stale/episode.mkv") as RemotelyCachedEntity

        //when: the arr renames the directory and the file during playback, then the stream fails
        sardine.move(webdav("/downloads/stale"), webdav("/downloads/stale-renamed"))
        sardine.move(webdav("/downloads/stale-renamed/episode.mkv"), webdav("/downloads/stale-renamed/renamed.mkv"))
        DebridFileResource(
            heldByTheStream, databaseFileService, streamingService, debridLinkService, debridavConfigurationProperties
        ).sendContent(ByteArrayOutputStream(), null, null, null)

        //then: the move stands, and the failure was recorded on the moved file
        assertThat(
            "file at its old path",
            databaseFileService.getFileAtPath("/downloads/stale/episode.mkv"),
            nullValue()
        )
        val moved = databaseFileService.getFileAtPath("/downloads/stale-renamed/renamed.mkv") as RemotelyCachedEntity?
        assertThat("file at its new path", moved, notNullValue())
        assertThat(
            "the moved file's Premiumize link after the failed stream",
            moved!!.contents!!.debridLinks.single { it.provider == DebridProvider.PREMIUMIZE },
            not(instanceOf(CachedFile::class.java))
        )
        assertThat("renamed directory still listable", sardine.list(webdav("/downloads/stale-renamed")).size, `is`(2))
    }

    private fun webdav(path: String) = "http://localhost:$serverPort/webdav$path"

    private fun contents(link: String) = DebridCachedTorrentContent(
        originalPath = "episode.mkv",
        size = 9L,
        modified = Instant.EPOCH.toEpochMilli(),
        magnet = MAGNET,
        mimeType = "video/x-matroska",
        debridLinks = mutableListOf(
            CachedFile(
                path = "episode.mkv",
                size = 9L,
                mimeType = "video/x-matroska",
                link = link,
                lastChecked = Instant.now().toEpochMilli(),
                params = hashMapOf(),
                provider = DebridProvider.PREMIUMIZE,
            )
        )
    )
}
