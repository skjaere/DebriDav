package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.MAGNET
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.test.integrationtest.config.PremiumizeStubbingService
import io.skjaere.debridav.test.integrationtest.config.ArrStubbingService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.verify.VerificationTimes
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class ArrsIT {
    @Autowired
    lateinit var sonarrStubbingService: ArrStubbingService

    @Autowired
    lateinit var premiumizeStubbingService: PremiumizeStubbingService

    @Autowired
    lateinit var mockserverClient: ClientAndServer

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `That uncached magnet gets marked as failed in Sonarr`() {
        //given
        sonarrStubbingService.stubParseResponse(ArrStubbingService.ArrService.SONARR)
        sonarrStubbingService.stubHistoryResponse(ArrStubbingService.ArrService.SONARR)
        sonarrStubbingService.stubFailedResponse(ArrStubbingService.ArrService.SONARR)
        premiumizeStubbingService.mockIsNotCached()

        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "tv-sonarr")
        parts.part("paused", "false")

        //when

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        //then
        await().untilAsserted {
            mockserverClient.verify(
                request()
                    .withMethod("POST")
                    .withPath("/sonarr/api/v3/history/failed/2"), VerificationTimes.once()
            )
        }
    }

    @Test
    fun `That uncached magnet gets marked as failed in Radarr`() {
        //given
        sonarrStubbingService.stubParseResponse(ArrStubbingService.ArrService.RADARR)
        sonarrStubbingService.stubHistoryResponse(ArrStubbingService.ArrService.RADARR)
        sonarrStubbingService.stubFailedResponse(ArrStubbingService.ArrService.RADARR)
        premiumizeStubbingService.mockIsNotCached()

        val parts = MultipartBodyBuilder()
        parts.part("urls", MAGNET)
        parts.part("category", "radarr")
        parts.part("paused", "false")

        //when

        webTestClient
            .mutate()
            .responseTimeout(Duration.ofMillis(30000))
            .build()
            .post()
            .uri("/api/v2/torrents/add")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(parts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful

        //then
        await().untilAsserted {
            mockserverClient.verify(
                request()
                    .withMethod("POST")
                    .withPath("/radarr/api/v3/history/failed/2"), VerificationTimes.once()
            )
        }
    }
}
