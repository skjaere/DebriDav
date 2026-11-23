package io.skjaere.debridav.ui

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private const val DEBRIDAV_FOLDER = "debridav"

@Service
class GrafanaDashboardService(
    private val uiConfig: UiConfigurationProperties,
    private val httpClient: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(GrafanaDashboardService::class.java)

    suspend fun listDashboards(): List<DashboardDto> {
        val baseUrl = uiConfig.grafana.baseUrl.trimEnd('/')
        if (baseUrl.isBlank()) return emptyList()
        val apiKey = uiConfig.grafana.apiKey.ifBlank { null }

        return try {
            val response = httpClient.get("$baseUrl/api/search?type=dash-db") {
                accept(ContentType.Application.Json)
                if (apiKey != null) bearerAuth(apiKey)
            }
            if (response.status.isSuccess()) {
                val entries: List<GrafanaSearchEntry> = response.body()
                entries
                    .filter { it.folderTitle == DEBRIDAV_FOLDER }
                    .map { DashboardDto(label = it.title, path = it.url) }
                    .sortedBy { it.label }
            } else {
                logger.warn("Grafana returned {} when listing dashboards", response.status)
                emptyList()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.warn("Failed to fetch dashboards from Grafana at {}: {}", baseUrl, e.message)
            emptyList()
        }
    }

    @Serializable
    private data class GrafanaSearchEntry(
        val title: String,
        val url: String,
        val folderTitle: String? = null,
    )
}
