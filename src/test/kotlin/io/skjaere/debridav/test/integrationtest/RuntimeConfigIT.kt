package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.arrs.ArrService
import io.skjaere.debridav.arrs.SonarrConfigurationProperties
import io.skjaere.debridav.config.ConfigOverrideService
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.UsenetRelease
import io.skjaere.debridav.debrid.client.easynews.EasynewsClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.test.integrationtest.config.EasynewsStubbingService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.Base64

/**
 * Settings changed while the app runs, through the UI's config overrides, must reach the code that
 * uses them, not only the bound properties.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class RuntimeConfigIT {

    @Autowired
    lateinit var arrService: ArrService

    @Autowired
    lateinit var sonarrConfigurationProperties: SonarrConfigurationProperties

    @Autowired
    lateinit var configOverrideService: ConfigOverrideService

    @Autowired
    lateinit var easynewsClient: EasynewsClient

    @Autowired
    lateinit var easynewsStubbingService: EasynewsStubbingService

    @Autowired
    lateinit var realDebridClient: RealDebridClient

    @Autowired
    lateinit var realDebridConfigurationProperties: RealDebridConfigurationProperties

    @Autowired
    lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    lateinit var debridavConfigurationProperties: DebridavConfigurationProperties

    @Autowired
    lateinit var mockServer: ClientAndServer

    // The context is shared with other test classes: put back what the tests change.
    private lateinit var debridClients: List<DebridProvider>
    private var sonarrEnabled = false
    private var syncEnabled = false

    @BeforeEach
    fun rememberSettings() {
        debridClients = debridavConfigurationProperties.debridClients
        sonarrEnabled = sonarrConfigurationProperties.integrationEnabled
        syncEnabled = realDebridConfigurationProperties.syncEnabled
    }

    @AfterEach
    fun tearDown() {
        sonarrConfigurationProperties.integrationEnabled = sonarrEnabled
        realDebridConfigurationProperties.syncEnabled = syncEnabled
        debridavConfigurationProperties.debridClients = debridClients
        listOf("easynews.username", "easynews.password").forEach { key ->
            runCatching { configOverrideService.delete(key) }
        }
        mockServer.reset()
    }

    @Test
    fun `turning Sonarr integration off at runtime stops it being used`() {
        assertThat(arrService.getClientForCategory(sonarrConfigurationProperties.category), notNullValue())

        sonarrConfigurationProperties.integrationEnabled = false

        assertThat(arrService.getClientForCategory(sonarrConfigurationProperties.category), nullValue())
    }

    @Test
    fun `Easynews credentials changed at runtime are sent with the next request`() {
        //given
        easynewsStubbingService.mockIsNotCached()
        configOverrideService.upsert("easynews.username", "new-user")
        configOverrideService.upsert("easynews.password", "new-password")

        //when
        runBlocking { easynewsClient.isCached(UsenetRelease("Some.Release.2024.1080p")) }

        //then
        val expected = "Basic " + Base64.getEncoder().encodeToString("new-user:new-password".toByteArray())
        mockServer.verify(request().withHeader("Authorization", expected), VerificationTimes.atLeast(1))
    }

    @Test
    fun `turning Real-Debrid sync on at runtime makes the sync task sync`() {
        //given
        realDebridStubbingService.stubEmptyTorrentsListResponse()
        realDebridStubbingService.stubEmptyDownloadsResponse()
        debridavConfigurationProperties.debridClients = listOf(DebridProvider.REAL_DEBRID)
        realDebridConfigurationProperties.syncEnabled = true

        //when
        realDebridClient.syncTorrentsTask()

        //then
        mockServer.verify(request().withMethod("GET").withPath("/realdebrid/torrents/"), VerificationTimes.atLeast(1))
    }
}
