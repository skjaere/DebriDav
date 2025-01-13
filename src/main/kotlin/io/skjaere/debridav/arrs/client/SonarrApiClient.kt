package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.skjaere.debridav.arrs.SonarrConfigurationProperties
import io.skjaere.debridav.arrs.client.models.sonarr.SonarrParseResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component

@Component
@ConditionalOnExpression("\${sonarr.integration-enabled:true}")
class SonarrApiClient(
    httpClient: HttpClient,
    sonarrConfigurationProperties: SonarrConfigurationProperties
) : BaseArrClient by DefaultBaseArrClient(httpClient, sonarrConfigurationProperties),
    ArrClient
{
    override suspend fun getItemIdFromName(name: String): Long? {
        return parse(name).body<SonarrParseResponse>().episodes.firstOrNull()?.id
    }

    override fun getCategory(): String = "tv-sonarr"
}
