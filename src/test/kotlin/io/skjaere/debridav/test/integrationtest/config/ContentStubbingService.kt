package io.skjaere.debridav.test.integrationtest.config

import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.Header
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ContentStubbingService(@Value("\${mockserver.port}") val port: Int) {
    val oneHundredKilobytes = IntRange(1, 1024 * 100).map { Byte.MAX_VALUE }.toByteArray()

    fun getBytes(startByte: Int, endByte: Int): ByteArray =
        IntRange(startByte, endByte).map { Byte.MAX_VALUE }.toByteArray()

    fun mock100kbRangeStream(startByte: Int, endByte: Int) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                //.withHeader(Header("Range", "bytes=0-3"))
                .withPath(
                    "/workingLink"
                ),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(206)
                .withBody(getBytes(startByte, endByte))
                .withHeader(Header("content-range", "bytes $startByte-$endByte/${endByte + 1}"))
        )
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("HEAD")
                .withPath(
                    "/workingLink"
                ),
            Times.exactly(2)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
        )
    }

    fun mock100kbRangeStreamWithDelay(startByte: Int, endByte: Int) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                //.withHeader(Header("Range", "bytes=0-3"))
                .withPath(
                    "/workingLink"
                ),
            //Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withDelay(org.mockserver.model.Delay.milliseconds(500))
                .withStatusCode(206)
                .withBody(oneHundredKilobytes)
                .withHeader(Header("content-range", "bytes $startByte-$endByte/${endByte + 1}"))
        )
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("HEAD")
                .withPath(
                    "/workingLink"
                ),
            Times.exactly(2)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
        )


    }

    fun mockWorkingRangeStream() = mockWorkingRangeStream(0L, 3L, 8L, "it w".toByteArray())
    fun mockWorkingRangeStream(start: Long, end: Long, totalSize: Long, response: ByteArray) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withHeader(Header("Range", "bytes=$start-$end"))
                .withPath(
                    "/workingLink"
                ),
            Times.exactly(1)
        ).respond(
            HttpResponse.response()
                .withStatusCode(206)
                .withBody(response)
                .withHeader(Header("content-range", "bytes $start-$end/$totalSize"))
        )
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("HEAD")
                .withPath(
                    "/workingLink"
                ),
            Times.exactly(2)
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
        )
    }

    fun mockWorkingStream(url: String) = mockWorkingStream(url, "it works!".toByteArray())
    fun mockWorkingStream() = mockWorkingStream("/workingLink", "it works!".toByteArray())
    fun mockWorkingStream(path: String, response: ByteArray) {
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
                .withBody(response)
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
            Times.exactly(3)
        ).respond(
            HttpResponse.response()
                .withStatusCode(404)
        )
    }
}
