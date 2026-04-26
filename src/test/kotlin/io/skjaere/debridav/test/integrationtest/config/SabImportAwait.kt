package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.debridav.usenet.sabnzbd.model.SabnzbdFullHistoryResponse
import kotlinx.serialization.json.Json
import org.awaitility.Awaitility.await
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import java.time.Duration

private val deserializer = Json { ignoreUnknownKeys = true }

/**
 * Polls the SABnzbd `/api?mode=history` endpoint until the named release reports
 * COMPLETED. Throws if the slot reports FAILED, or if the timeout elapses without
 * a COMPLETED status.
 */
fun WebTestClient.awaitSabImportCompletion(
    releaseName: String,
    timeout: Duration = Duration.ofSeconds(30),
) = awaitSabImportStatus(releaseName, expected = "COMPLETED", terminalErrors = setOf("FAILED"), timeout = timeout)

/**
 * Polls the SABnzbd `/api?mode=history` endpoint until the named release reports
 * FAILED. Throws if the slot reports COMPLETED, or if the timeout elapses.
 */
fun WebTestClient.awaitSabImportFailure(
    releaseName: String,
    timeout: Duration = Duration.ofSeconds(30),
) = awaitSabImportStatus(releaseName, expected = "FAILED", terminalErrors = setOf("COMPLETED"), timeout = timeout)

private fun WebTestClient.awaitSabImportStatus(
    releaseName: String,
    expected: String,
    terminalErrors: Set<String>,
    timeout: Duration,
) {
    await().atMost(timeout).until {
        val historyParts = MultipartBodyBuilder().apply { part("mode", "history") }
        val body = post().uri("/api")
            .body(BodyInserters.fromMultipartData(historyParts.build()))
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody(String::class.java)
            .returnResult().responseBody ?: return@until false
        val slot = deserializer.decodeFromString<SabnzbdFullHistoryResponse>(body)
            .history.slots.firstOrNull { it.name == releaseName }
        when (slot?.status) {
            expected -> true
            in terminalErrors -> error("Expected $expected but got ${slot?.status} for $releaseName")
            else -> false
        }
    }
}
