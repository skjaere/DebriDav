package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridTorrentRepository
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.RealDebridStubbingService
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.hasItems
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.Map

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class RealDebridSupportIT {
    @Autowired
    lateinit var realDebridStubbingService: RealDebridStubbingService

    @Autowired
    lateinit var realDebridTorrentRepository: RealDebridTorrentRepository

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @AfterEach
    fun tearDown() {
        mockserverClient.reset()
    }

    @Test
    fun `that torrents present in RD get added to database`() {
        // given
        realDebridStubbingService.stubTorrentsListResponse()
        realDebridStubbingService.stubMultipleDownloadsResponse()

        // when
        enableTorrentImport()

        // then
        await().untilAsserted {
            runBlocking {
                assertThat(
                    realDebridTorrentRepository.findAll().toList(), hasItems(
                        allOf(
                            hasProperty("hash", `is`("6638e282767b7c710ff561a5cfd4f7e4ceb5d448")),
                            hasProperty("torrentId", `is`("LD3PPDP4R4LAY"))
                        ),
                        allOf(
                            hasProperty("hash", `is`("6638e282767b7c710ff561a5cfd4f7e4ceb5d449")),
                            hasProperty("torrentId", `is`("LD3PPDP4R4LA1"))
                        ),
                        allOf(
                            hasProperty("hash", `is`("6638e282767b7c710ff561a5cfd4f7e4ceb5d450")),
                            hasProperty("torrentId", `is`("LD3PPDP4R4LA2"))
                        )

                    )
                )
            }

        }

    }

    @Test
    fun `that empty torrent list from api results in empty table`() {
        // given
        realDebridStubbingService.stubEmptyTorrentsListResponse()
        realDebridStubbingService.stubEmptyDownloadsResponse()
        val rdt = RealDebridTorrentEntity()
        rdt.torrentId = "LD3PPDP4R4LA2"
        rdt.name = "deleteme.txt"
        rdt.hash = "6638e282767b7c710ff561a5cfd4f7e4ceb5d451"
        runBlocking { realDebridTorrentRepository.save(rdt) }

        // when
        enableTorrentImport()

        // then
        await().untilAsserted {
            runBlocking {
                assertThat(
                    realDebridTorrentRepository.findAll().toList(), hasSize(0)
                )
            }

        }
    }

    @Test
    fun `that missing torrent gets removed, and new one gets added`() {
        // given
        realDebridStubbingService.stubSingleTorrentsListResponse()
        realDebridStubbingService.stubSingleDownloadsResponse()
        val rdt = RealDebridTorrentEntity()
        rdt.torrentId = "LD3PPDP4R4LA2"
        rdt.name = "deleteme.txt"
        rdt.hash = "6638e282767b7c710ff561a5cfd4f7e4ceb5d451"
        runBlocking { realDebridTorrentRepository.save(rdt) }

        // when
        enableTorrentImport()

        // then
        await().untilAsserted {
            runBlocking {
                assertThat(
                    realDebridTorrentRepository.findAll().toList(), allOf<List<RealDebridTorrentEntity>>(
                        hasSize(1),
                        hasItem<RealDebridTorrentEntity>(
                            hasProperty("torrentId", `is`("LD3PPDP4R4LAY"))
                        )
                    )
                )
            }
        }
    }

    private fun enableTorrentImport() {
        webTestClient
            .post()
            .uri("/actuator/realdebrid")
            .header("Content-Type", "application/json")
            .bodyValue(Map.of<String?, String?>("torrentImportEnabled", "true"))
            .exchange()
            .expectStatus().is2xxSuccessful()
    }
}
