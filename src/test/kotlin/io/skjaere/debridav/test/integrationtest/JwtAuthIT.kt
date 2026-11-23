package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.config.ConfigOverrideRepository
import io.skjaere.debridav.config.auth.JwtService
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.StreamUrlDto
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import kotlin.test.fail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

private const val JWT_SECRET = "test-secret-key-that-is-at-least-256-bits-long-for-hs256"

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "debridav.auth.jwt-secret=$JWT_SECRET",
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

    @LocalServerPort
    private var serverPort: Int = 0

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
    fun `collection endpoints require auth, but a play session's token is its own key`() {
        webTestClient.get().uri("/api/v1/collection/indexers").exchange().expectStatus().isUnauthorized
        webTestClient.get().uri("/api/v1/collection/movies").exchange().expectStatus().isUnauthorized
        // No session has this token: not found, never a login challenge.
        webTestClient.get().uri("/api/v1/collection/sessions/not-a-token/source").exchange().expectStatus().isNotFound
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

    // Sonarr/Radarr treat only 403 as "log in again" and probe webapiVersion without a
    // session, accepting 200, 403 or 404 there, as real qBittorrent answers.

    @Test
    fun `qbittorrent api answers 403 like qBittorrent when there is no session`() {
        webTestClient.get()
            .uri("/api/v2/app/webapiVersion")
            .exchange()
            .expectStatus().isForbidden
            .expectBody(String::class.java).isEqualTo("Forbidden")
    }

    @Test
    fun `qbittorrent api answers 403 to an expired session`() {
        val expired = loginToken(
            Keys.hmacShaKeyFor(JWT_SECRET.toByteArray()),
            expiresAt = Instant.now().minus(Duration.ofHours(1)),
        )

        webTestClient.get()
            .uri("/api/v2/torrents/info")
            .cookie("SID", expired)
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `qbittorrent api answers 403 to a session signed with another key`() {
        // What every session becomes on restart when no JWT secret is set.
        val fromBeforeRestart = loginToken(
            Jwts.SIG.HS256.key().build(),
            expiresAt = Instant.now().plus(Duration.ofHours(1)),
        )

        webTestClient.get()
            .uri("/api/v2/torrents/info")
            .cookie("SID", fromBeforeRestart)
            .exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `a qbittorrent client whose session is rejected can log in again`() {
        val staleSid = loginToken(Jwts.SIG.HS256.key().build(), expiresAt = Instant.now().plus(Duration.ofHours(1)))
        webTestClient.get()
            .uri("/api/v2/torrents/info")
            .cookie("SID", staleSid)
            .exchange()
            .expectStatus().isForbidden

        val sid = webTestClient.post()
            .uri("/api/v2/auth/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("username", "admin").with("password", "secret"))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("Ok.")
            .returnResult()
            .responseCookies.getFirst("SID")?.value
            ?: fail("login set no SID cookie")

        webTestClient.get()
            .uri("/api/v2/torrents/info")
            .cookie("SID", sid)
            .exchange()
            .expectStatus().isOk
    }

    private fun loginToken(key: SecretKey, expiresAt: Instant): String = Jwts.builder()
        .subject("admin")
        .claim("type", "login")
        .issuedAt(Date.from(expiresAt.minus(Duration.ofHours(24))))
        .expiration(Date.from(expiresAt))
        .signWith(key)
        .compact()

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
    fun `the api keeps the strict firewall that webdav relaxes`() {
        // /webdav/ accepts %25 and ; for file names; the API must still reject them.
        webTestClient.get()
            .uri(java.net.URI.create("http://localhost:$serverPort/api/v1/config%3Bx"))
            .header("Authorization", "Bearer ${jwtService.generateToken("admin")}")
            .exchange()
            .expectStatus().isBadRequest
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
            .expectStatus().isForbidden
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
