package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.debridav.debrid.client.realdebrid.model.TorrentsInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mockserver.client.MockServerClient
import org.mockserver.matchers.Times
import org.mockserver.model.HttpRequest
import org.mockserver.model.HttpResponse
import org.mockserver.model.MediaType
import org.mockserver.model.Parameter
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
                ), Times.exactly(3)
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

    fun stubTorrentsListResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/torrents/"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    Json.encodeToString<List<TorrentsInfo>>(
                        listOf(
                            getTorrentInfoItem(),
                            getTorrentInfoItem().copy(
                                hash = "6638e282767b7c710ff561a5cfd4f7e4ceb5d449",
                                id = "LD3PPDP4R4LA1"
                            ),
                            getTorrentInfoItem().copy(
                                hash = "6638e282767b7c710ff561a5cfd4f7e4ceb5d450",
                                id = "LD3PPDP4R4LA2"
                            )
                        )
                    )

                )
        )
    }

    fun stubTorrentInfoResponse(id: String, payload: String) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/torrents/info/$id"
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(payload)
        )
    }

    fun stubMultipleDownloadsResponse() {
        stubDownloadsResponse(getMultipleRealDebridDownloadItem())
    }

    fun stubDownloadsResponse(payload: String) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/downloads"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(payload)
        )
    }


    fun stubEmptyTorrentsListResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/torrents/"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(204)
                .withContentType(MediaType.APPLICATION_JSON)
        )
    }

    fun stubEmptyDownloadsResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/downloads"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(204)
                .withContentType(MediaType.APPLICATION_JSON)
        )
    }

    fun stubSingleTorrentsListResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/torrents/"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    Json.encodeToString<List<TorrentsInfo>>(
                        listOf(getTorrentInfoItem())
                    )
                )
        )
    }

    fun stubSingleDownloadsResponse() {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("GET")
                .withPath(
                    "/realdebrid/downloads"
                )
                .withQueryStringParameters(
                    Parameter("limit", "500")
                )
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(
                    getRealDebridDownloadItem()
                )
        )
    }

    fun stubUnrestrictLink(link: String, payload: String) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/realdebrid/unrestrict/link"
                )
                .withBody("link=$link")
        ).respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(payload)
        )
    }

    fun stubAddMagnet(magnet: String, payload: String) {
        MockServerClient(
            "localhost",
            port
        ).`when`(
            HttpRequest.request()
                .withMethod("POST")
                .withPath(
                    "/realdebrid/torrents/addMagnet"
                )
                .withBody("magnet=$magnet")
        ).respond(
            HttpResponse.response()
                .withStatusCode(201)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(payload)
        )
    }

    fun getTorrentInfoItem(): TorrentsInfo = TorrentsInfo(
        filename = "VENGEANCE.VALLEY.1951.DVDrip.Swesub.XviD-Mr_KeFF",
        originalFilename = "VENGEANCE.VALLEY.1951.DVDrip.Swesub.XviD-Mr_KeFF",
        id = "LD3PPDP4R4LAY",
        hash = "6638e282767b7c710ff561a5cfd4f7e4ceb5d448",
        bytes = 978152352L,
        originalBytes = 978152352L,
        status = "waiting_files_selection",
        host = "real-debrid.com",
        added = "2025-02-17T10:42:59.000Z",
        split = 2000,
        progress = 0,
        files = emptyList(),
        links = listOf(
            "https://real-debrid.com/d/UJE7C4NNFB4B2C6C"
        )
    )

    fun getRealDebridDownloadItem(): String = """
        [{
        "id": "UY2IPW4N7WVJW",
        "filename": "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv",
        "mimeType": "video/x-matroska",
        "filesize": 1373366001,
        "link": "https://real-debrid.com/d/UJE7C4NNFB4B2C6C",
        "host": "real-debrid.com",
        "host_icon": "https://fcdn.real-debrid.com/0830/images/hosters/realdebrid.png",
        "chunks": 32,
        "download": "https://22-4.download.real-debrid.com/d/UY2IPW4N7WVJW/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv",
        "streamable": 1,
        "generated": "2025-02-18T18:30:19.000Z"
    }]
    """.trimIndent()

    fun getMultipleRealDebridDownloadItem(): String = """
        [
            {
                "id": "EYUT2HP5DHF5Q",
                "filename": "Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv",
                "mimeType": "video/x-matroska",
                "filesize": 1373366001,
                "link": "https://real-debrid.com/d/7AULFT3RUWGL2CB2",
                "host": "real-debrid.com",
                "host_icon": "https://fcdn.real-debrid.com/0830/images/hosters/realdebrid.png",
                "chunks": 32,
                "download": "https://22-4.download.real-debrid.com/d/UY2IPW4N7WVJW/Vengeance.Valley.1951.DVDRip.x264.EAC3-SARTRE.mkv",
                "streamable": 1,
                "generated": "2025-02-18T18:30:19.000Z"
        },{
                "id": "UY2IPW4N7WVJN",
                "filename": "Vengeance.Valley.2.1951.DVDRip.x264.EAC3-SARTRE.mkv",
                "mimeType": "video/x-matroska",
                "filesize": 1373366002,
                "link": "https://real-debrid.com/d/UJE7C4NNFB4B2C6D",
                "host": "real-debrid.com",
                "host_icon": "https://fcdn.real-debrid.com/0830/images/hosters/realdebrid.png",
                "chunks": 32,
                "download": "https://22-4.download.real-debrid.com/d/UY2IPW4N7WVJM/Vengeance.Valley.2.1951.DVDRip.x264.EAC3-SARTRE.mkv",
                "streamable": 1,
                "generated": "2025-02-18T18:30:19.000Z"
        }
    ]
    """.trimIndent()

}
