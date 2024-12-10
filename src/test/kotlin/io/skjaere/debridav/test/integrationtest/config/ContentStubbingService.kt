package io.skjaere.debridav.test.integrationtest.config

import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ContentStubbingService(@Value("\${mockserver.port}") val port: Int) {
    fun mockWorkingStream() = mockWorkingStream("/workingLink")
    fun mockWorkingStream(path: String) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    path
                ),
            Times.exactly(2)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody("it works!")
        )
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("HEAD")
                .withPath(
                    path
                ),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
        )
    }

    fun mockDeadLink() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "deadLink"
                ),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(404)
        )
    }
}
