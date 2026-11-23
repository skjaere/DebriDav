package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

// Runs against the production SecurityConfiguration (no TestSecurityConfiguration import)
// with WebDAV credentials deliberately *unset* to exercise the fail-closed branch in
// QBittorrentEmulationController.login.
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "debridav.debrid-clients=premiumize",
        "debridav.auth.jwt-secret=test-secret-key-that-is-at-least-256-bits-long-for-hs256"
        // Intentionally NO debridav.webdav-username / webdav-password
    ]
)
@MockServerTest
class QBittorrentAuthFailClosedIT {

    @Autowired
    private lateinit var webTestClient: WebTestClient

    @Test
    fun `qB login refuses to mint a token when WebDAV creds are not configured`() {
        webTestClient.post()
            .uri("/api/v2/auth/login")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("username=anyone&password=whatever")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .isEqualTo("Fails.")
    }
}
