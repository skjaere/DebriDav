package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.config.ConfigOverrideRepository
import io.skjaere.debridav.config.auth.JwtService
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "debridav.auth.jwt-secret=test-secret-key-that-is-at-least-256-bits-long-for-hs256",
        "debridav.webdav-username=admin",
        "debridav.webdav-password=secret"
    ]
)
@MockServerTest
class JwtAuthIT {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var configOverrideRepository: ConfigOverrideRepository

    @AfterEach
    fun tearDown() {
        configOverrideRepository.deleteAll()
    }

    @Test
    fun `login with valid credentials returns token`() {
        webTestClient.post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username": "admin", "password": "secret"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.token").isNotEmpty
    }

    @Test
    fun `login with invalid credentials returns 401`() {
        webTestClient.post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username": "admin", "password": "wrong"}""")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `config endpoint requires auth when enabled`() {
        webTestClient.get()
            .uri("/api/v1/config")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `config endpoint accessible with valid token`() {
        val token = jwtService.generateToken("admin")

        webTestClient.get()
            .uri("/api/v1/config")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `config endpoint rejects invalid token`() {
        webTestClient.get()
            .uri("/api/v1/config")
            .header("Authorization", "Bearer invalid-token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `qbittorrent api requires auth when configured`() {
        webTestClient.get()
            .uri("/api/v2/app/webapiVersion")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `qbittorrent api accessible with valid token`() {
        val token = jwtService.generateToken("admin")

        webTestClient.get()
            .uri("/api/v2/app/webapiVersion")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `auth endpoint is always public`() {
        webTestClient.post()
            .uri("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username": "admin", "password": "secret"}""")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `sabnzbd api accepts Bearer JWT instead of apikey for UI uploads`() {
        // The bundled frontend's add-NZB form posts to /api with a Bearer JWT (it
        // doesn't have access to the SABnzbd apikey — that's set on the
        // DebridavConfigurationProperties side and only known to Sonarr/Radarr).
        // The controller must accept the JWT as an alternative to apikey, otherwise
        // every UI upload 401s.
        val token = jwtService.generateToken("admin")

        webTestClient.post()
            .uri("/api?mode=version")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.version").isEqualTo("4.4.0")
    }

    @Test
    fun `sabnzbd api still rejects requests without auth or apikey`() {
        webTestClient.post()
            .uri("/api?mode=version")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
