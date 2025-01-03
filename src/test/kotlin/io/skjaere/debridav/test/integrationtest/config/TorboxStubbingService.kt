package io.skjaere.debridav.test.integrationtest.config

import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TorboxStubbingService(
    @Value("\${mockserver.port}") val port: Int
) {
    fun reset() {
        MockServerClient(
            "localhost",
            port
        ).reset()
    }

    fun stubAddNzbResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/torbox/v1/api/usenet/createusenetdownload"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                            {
                                "success": true,
                                "error": null,
                                "detail": "Download started successfully",
                                "data": {
                                    "hash": "XXXXXXXXXXXXXXXXX",
                                    "usenetdownload_id": "0",
                                    "auth_id": "XXXXXXXXXXXXXXXXX"
                                }
                            }
                            """
                )
        )
    }

    @Suppress("LongMethod")
    fun stubUsenetListResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/torbox/v1/api/usenet/mylist"
                ).withQueryStringParameter("id", "0")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                            {
                                "success": true,
                                "error": null,
                                "detail": "Usenet downloads list retrieved successfully.",
                                "data": {
                                    "id": 0,
                                    "created_at": "2024-12-30T18:28:41Z",
                                    "updated_at": "2024-12-30T18:28:41Z",
                                    "auth_id": "xxx",
                                    "name": "directory",
                                    "hash": "xxx",
                                    "download_state": "cached",
                                    "download_speed": 0,
                                    "original_url": "None",
                                    "eta": 0,
                                    "progress": 1,
                                    "size": 10,
                                    "download_id": "xxx",
                                    "files": [
                                        {
                                            "id": 0,
                                            "md5": null,
                                            "hash": "b1b2aed31cbc3ce9b87407a860e87086",
                                            "name": "junkName/video.mkv",
                                            "size": 3956004321,
                                            "zipped": false,
                                            "s3_path": "b1b2aed31cbc3ce9b87407a860e87086/directory/video.mkv",
                                            "infected": false,
                                            "mimetype": "video/x-matroska",
                                            "short_name": "video.mkv",
                                            "absolute_path": "/downloads/b1b2aed31cbc3ce9b87407a860e87086/directory/video.mkv"
                                        }
                                    ],
                                    "active": false,
                                    "cached": true,
                                    "download_present": true,
                                    "download_finished": true,
                                    "expires_at": null,
                                    "server": null
                                }
                            }
                            """
                )
        )
    }

    fun stubMissingListResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/torbox/v1/api/usenet/mylist"
                ).withQueryStringParameter("id", "0")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                            {
                                "success": false,
                                "error": "DATABASE_ERROR",
                                "detail": "Failed to retrieve usenet downloads list. Please try again later.",
                                "data": null
                            }
                            """
                )
        )
    }

    fun stubUsenetDownloadLink() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/torbox/v1/api/usenet/requestdl"
                )
                .withQueryStringParameter("usenet_id", "0")
                .withQueryStringParameter("token", "asd")
                .withQueryStringParameter("fileId", "0")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    """
                            {
                                "success": true,
                                "error": null,
                                "detail": "Usenet download requested successfully.",
                                "data": "https://localhost:$port/video.mkv"
                            }
                            """
                )
        )
    }
}
