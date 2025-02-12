package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.skjaere.debridav.arrs.RadarrConfigurationProperties
import io.skjaere.debridav.arrs.client.models.radarr.RadarrParseResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${radarr.integration-enabled:true}")
class RadarrApiClient(
    httpClient: HttpClient,
    private val radarrConfigurationProperties: RadarrConfigurationProperties
) : BaseArrClient by DefaultBaseArrClient(httpClient, radarrConfigurationProperties),
    ArrClient {
    override suspend fun getItemIdFromName(name: String): Long {
        return parse(name).body<RadarrParseResponse>().movie.id
    }

    override fun getCategory(): String = radarrConfigurationProperties.category
}
