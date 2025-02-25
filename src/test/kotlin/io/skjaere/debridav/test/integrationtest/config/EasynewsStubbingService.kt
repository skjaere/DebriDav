package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.debridav.debrid.client.easynews.SearchResults
import kotlinx.serialization.json.Json
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class EasynewsStubbingService(
    @Value("\${mockserver.port}") val port: Int
) {
    fun mockIsNotCached() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/easynews/2.0/search/solr-search/"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    Json.encodeToString(
                        SearchResults.serializer(),
                        SearchResults(
                            dlPort = 80,
                            dlFarm = "farm",
                            downUrl = "",
                            sid = "sid",
                            data = listOf()
                        )
                    )
                )
        )
    }

    fun mockIsCached() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/easynews/2.0/search/solr-search/"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    Json.encodeToString(
                        SearchResults.serializer(),
                        SearchResults(
                            dlPort = 80,
                            dlFarm = "farm",
                            downUrl = "http://localhost:$port/easynews/contentLink.mkv",
                            sid = "sid",
                            data = listOf(
                                SearchResults.Item(
                                    hash = "hash",
                                    ext = ".mkv",
                                    releaseName = "releaseName",
                                    id = "id",
                                    sig = "sig",
                                    rawSize = 100L,
                                    size = 100L,
                                    runtime = 1200L
                                )
                            )
                        )
                    )
                )
        )
    }

    fun stubWorkingLink() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("HEAD")
                .withPath("/easynews/dl/farm/80/hashid.mkv/releaseName.mkv")
                .withQueryStringParameter("sid", "sid:0")
                .withQueryStringParameter("sig", "sig")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("ok!")
        )

    }
}
