package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.debridav.arrs.client.models.HistoryResponse
import io.skjaere.debridav.arrs.client.models.HistoryResponse.HistoryRecord
import io.skjaere.debridav.arrs.client.models.radarr.RadarrParseResponse
import io.skjaere.debridav.arrs.client.models.sonarr.SonarrParseResponse
import kotlinx.serialization.json.Json
import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ArrStubbingService(@Value("\${mockserver.port}") val port: Int) {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun stubParseResponse(arr: ArrService) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "${getPath(arr)}/api/v3/parse"
                ).withQueryStringParameter("title", "test")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(

                    when (arr) {
                        ArrService.SONARR -> jsonParser.encodeToString(
                            SonarrParseResponse.serializer(), SonarrParseResponse(
                                listOf(SonarrParseResponse.Episode(1L))
                            )
                        )

                        ArrService.RADARR -> jsonParser.encodeToString(
                            RadarrParseResponse.serializer(), RadarrParseResponse(
                                RadarrParseResponse.Movie(1L)
                            )
                        )
                    }
                )
        )
    }

    fun stubHistoryResponse(arr: ArrService) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "${getPath(arr)}/api/v3/history"
                ).withQueryStringParameter("episodeId", "1"), Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    jsonParser.encodeToString(
                        HistoryResponse.serializer(), HistoryResponse(
                            listOf(HistoryRecord("grabbed", 2L))
                        )
                    )
                )
        )
    }

    fun stubFailedResponse(arr: ArrService) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "${getPath(arr)}/api/v3/history/failed/2"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)

        )
    }

    fun getPath(arr: ArrService): String =
        when (arr) {
            ArrService.SONARR -> "/sonarr"
            ArrService.RADARR -> "/radarr"
        }

    enum class ArrService { SONARR, RADARR }
}
