package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.debridav.debrid.client.premiumize.model.Content
import io.skjaere.debridav.debrid.client.premiumize.model.SuccessfulDirectDownloadResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value

class PremiumizeStubbingService(
    @Value("\${mockserver.port}") val port: Int
) {
    fun reset() = MockServerClient("localhost", port).reset()
    fun mockIsCached() {

        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/premiumize/cache/check"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                            {
                                "response":[true],
                                "status": "ok",
                                "transcoded":["true"],
                                "filename":["filename"],
                                "filesize":[100]
                            }
                            """
                )
        )
    }

    fun mockIsNotCached() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/premiumize/cache/check"
                ),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                    {
                      "status": "success",
                      "response": [
                        false
                      ],
                      "transcoded": [
                        false
                      ],
                      "filename": [],
                      "filesize": []
                    }
                """.trimIndent()
                )
        )
    }

    fun mockCachedContents() {
        val response = SuccessfulDirectDownloadResponse(
            "success",
            "location",
            "filename",
            100,
            listOf(
                Content(
                    "a/b/c/movie.mkv",
                    100000000,
                    "http://localhost:$port/workingLink",
                    null,
                    null
                )
            )
        )
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/premiumize/transfer/directdl"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(Json.encodeToString(response))
        )
    }

    fun mock503ResponseCachedContents() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/premiumize/transfer/directdl"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(503)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody("this is a mocked error")
        )
    }
}
