package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.config.ConfigOverrideRepository
import io.skjaere.debridav.config.DatabasePropertySourceInitializer
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeConfigurationProperties
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import kotlin.test.assertEquals

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "debridav.auth.enabled=false",
        "debridav.auth.jwt-secret=test-secret-key-that-is-at-least-256-bits-long-for-hs256"
    ]
)
@MockServerTest
class ConfigApiIT {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var configOverrideRepository: ConfigOverrideRepository

    @Autowired
    private lateinit var debridavConfig: DebridavConfigurationProperties

    @Autowired
    private lateinit var premiumizeConfig: PremiumizeConfigurationProperties

    @Autowired
    private lateinit var contextRefresher: ContextRefresher

    @Autowired
    private lateinit var dbPropertySourceInitializer: DatabasePropertySourceInitializer

    @AfterEach
    fun tearDown() {
        configOverrideRepository.deleteAll()
        val propertySource = dbPropertySourceInitializer.getOrCreatePropertySource()
        propertySource.replaceAll(emptyMap())
        contextRefresher.refreshEnvironment()
    }

    @Test
    fun `list all whitelisted config properties`() {
        webTestClient.get()
            .uri("/api/v1/config")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$").isArray
            .jsonPath("$.length()").isNotEmpty
            .jsonPath("$[?(@.key == 'debridav.download-path')]").exists()
            .jsonPath("$[?(@.key == 'debridav.download-path')].name").isEqualTo("Download Path")
            .jsonPath("$[?(@.key == 'debridav.download-path')].type").isEqualTo("STRING")
            .jsonPath("$[?(@.key == 'debridav.should-delete-non-working-files')].type").isEqualTo("BOOLEAN")
            .jsonPath("$[?(@.key == 'debridav.torrent-lifetime')].type").isEqualTo("DURATION")
            .jsonPath("$[?(@.key == 'spring.datasource.url')]").doesNotExist()
    }

    @Test
    fun `get single config property`() {
        webTestClient.get()
            .uri("/api/v1/config/debridav.download-path")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.key").isEqualTo("debridav.download-path")
            .jsonPath("$.name").isEqualTo("Download Path")
            .jsonPath("$.hasOverride").isEqualTo(false)
            .jsonPath("$.group").isEqualTo("debridav")
            .jsonPath("$.type").isEqualTo("STRING")
    }

    @Test
    fun `upsert creates override`() {
        webTestClient.put()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "2h"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.key").isEqualTo("debridav.torrent-lifetime")
            .jsonPath("$.name").isEqualTo("Torrent Lifetime")
            .jsonPath("$.hasOverride").isEqualTo(true)
            .jsonPath("$.effectiveValue").isEqualTo("2h")
            .jsonPath("$.type").isEqualTo("DURATION")
    }

    @Test
    fun `delete removes override and reverts to default`() {
        // First create an override
        webTestClient.put()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "2h"}""")
            .exchange()
            .expectStatus().isOk

        // Then delete it
        webTestClient.delete()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.hasOverride").isEqualTo(false)
    }

    @Test
    fun `non-whitelisted key returns 400`() {
        webTestClient.get()
            .uri("/api/v1/config/spring.datasource.url")
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.error").exists()
    }

    @Test
    fun `upsert non-whitelisted key returns 400`() {
        webTestClient.put()
            .uri("/api/v1/config/spring.datasource.url")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "jdbc:postgresql://evil:5432/db"}""")
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `sensitive values are masked in responses`() {
        // Upsert a sensitive value
        webTestClient.put()
            .uri("/api/v1/config/premiumize.api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "my-secret-key"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.sensitive").isEqualTo(true)
            .jsonPath("$.effectiveValue").isEqualTo("***")
    }

    @Test
    fun `delete non-existent override returns 404`() {
        webTestClient.delete()
            .uri("/api/v1/config/debridav.download-path")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
    fun `upsert refreshes config bean at runtime`() {
        val originalLifetime = debridavConfig.torrentLifetime

        webTestClient.put()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "2h"}""")
            .exchange()
            .expectStatus().isOk

        assertEquals(Duration.ofHours(2), debridavConfig.torrentLifetime)
        assert(debridavConfig.torrentLifetime != originalLifetime) {
            "torrentLifetime should have changed from default $originalLifetime"
        }
    }

    @Test
    fun `delete reverts config bean to default at runtime`() {
        val originalLifetime = debridavConfig.torrentLifetime

        // Override
        webTestClient.put()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "2h"}""")
            .exchange()
            .expectStatus().isOk
        assertEquals(Duration.ofHours(2), debridavConfig.torrentLifetime)

        // Delete override
        webTestClient.delete()
            .uri("/api/v1/config/debridav.torrent-lifetime")
            .exchange()
            .expectStatus().isOk

        assertEquals(originalLifetime, debridavConfig.torrentLifetime)
    }

    @Test
    fun `upsert refreshes boolean property on config bean`() {
        val original = debridavConfig.shouldDeleteNonWorkingFiles

        webTestClient.put()
            .uri("/api/v1/config/debridav.should-delete-non-working-files")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "${!original}"}""")
            .exchange()
            .expectStatus().isOk

        assertEquals(!original, debridavConfig.shouldDeleteNonWorkingFiles)
    }

    @Test
    fun `upsert refreshes property on different config bean`() {
        webTestClient.put()
            .uri("/api/v1/config/premiumize.api-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"value": "new-api-key-12345"}""")
            .exchange()
            .expectStatus().isOk

        assertEquals("new-api-key-12345", premiumizeConfig.apiKey)
    }

    @Test
    fun `saving nntp pools twice does not cause duplicate key violation`() {
        val poolJson = """[{"host":"news.example.com","port":563,"username":"user",""" +
            """"password":"pass","useTls":true,"maxConnections":8,"priority":0}]"""

        // First save
        webTestClient.put()
            .uri("/api/v1/config/nntp-pools")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(poolJson)
            .exchange()
            .expectStatus().isOk

        val updatedPoolJson = """[{"host":"news2.example.com","port":563,"username":"user2",""" +
            """"password":"pass2","useTls":true,"maxConnections":4,"priority":0}]"""

        // Second save - should not throw DataIntegrityViolationException
        webTestClient.put()
            .uri("/api/v1/config/nntp-pools")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(updatedPoolJson)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].host").isEqualTo("news2.example.com")
            .jsonPath("$[0].maxConnections").isEqualTo(4)
    }
}
