package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.config.ConfigOverrideRepository
import io.skjaere.debridav.config.auth.JwtService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.StreamUrlDto
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

    @Autowired
    private lateinit var databaseFileService: DatabaseFileService

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

    @Test
    fun `qbittorrent api accessible with a login token in the SID cookie`() {
        val token = jwtService.generateToken("admin")

        webTestClient.get()
            .uri("/api/v2/app/webapiVersion")
            .cookie("SID", token)
            .exchange()
            .expectStatus().isOk
    }

    // A stream URL carries its token in the path for 24 hours and ends up in player
    // histories, proxy logs and shared links. Whoever holds one may play that one file,
    // and must not be able to use the token as a login anywhere a login is accepted.

    @Test
    fun `stream token is not accepted as a Bearer login`() {
        val token = jwtService.generateStreamToken("/downloads/Some.Movie.2024.mkv")

        webTestClient.get()
            .uri("/api/v1/config")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `stream token is not accepted as a qbittorrent SID cookie`() {
        val token = jwtService.generateStreamToken("/downloads/Some.Movie.2024.mkv")

        webTestClient.get()
            .uri("/api/v2/app/webapiVersion")
            .cookie("SID", token)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `sabnzbd api rejects a stream token as Bearer`() {
        val token = jwtService.generateStreamToken("/downloads/Some.Movie.2024.mkv")

        webTestClient.post()
            .uri("/api?mode=version")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `stream url plays its file but its token is no login`() {
        val contents = "the contents of the streamed file".toByteArray()
        val path = "/jwt-auth-it/clip.txt"
        val file = databaseFileService.createLocalFile(path, contents.inputStream(), contents.size.toLong())
        try {
            val loginToken = jwtService.generateToken("admin")
            val streamUrl = webTestClient.get()
                .uri { it.path("/api/v1/files/stream-url").queryParam("path", path).build() }
                .header("Authorization", "Bearer $loginToken")
                .exchange()
                .expectStatus().isOk
                .expectBody(StreamUrlDto::class.java)
                .returnResult()
                .responseBody!!
                .url

            webTestClient.get()
                .uri(streamUrl)
                .exchange()
                .expectStatus().isOk
                .expectBody(ByteArray::class.java)
                .isEqualTo(contents)

            val streamToken = streamUrl.substringAfterLast("/")
            webTestClient.get()
                .uri("/api/v1/config")
                .header("Authorization", "Bearer $streamToken")
                .exchange()
                .expectStatus().isUnauthorized
        } finally {
            databaseFileService.deleteFile(file)
        }
    }

    @Test
    fun `stream endpoint rejects a login token`() {
        val token = jwtService.generateToken("admin")

        webTestClient.get()
            .uri("/api/v1/stream/t/$token")
            .exchange()
            .expectStatus().isUnauthorized
    }
}
