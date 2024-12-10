package io.skjaere.debridav.test.integrationtest.config

import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class RealDebridStubbingService(
    @Value("\${mockserver.port}") val port: Int
) {

    fun mock503AddMagnetResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/realdebrid/torrents/addMagnet"
                )
            , Times.exactly(3)
        ).respond(
            HttpResponse.response()
                .withStatusCode(503)
                .withContentType(MediaType.APPLICATION_JSON)
        )
    }

    fun mock400AddMagnetResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/realdebrid/torrents/addMagnet"
                ),
            Times.once()
        ).respond(
            HttpResponse.response()
                .withStatusCode(400)
                .withContentType(MediaType.APPLICATION_JSON)
        )
    }
}
