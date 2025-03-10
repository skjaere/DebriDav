package io.skjaere.debridav.usenet.sabnzbd

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
class SabnzbdApiController(
    private val resourceLoader: ResourceLoader,
    private val sabNzbdService: SabNzbdService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties
) {
    private val logger = LoggerFactory.getLogger(SabnzbdApiController::class.java)

    @RequestMapping(
        path = ["/api"],
        method = [RequestMethod.GET, RequestMethod.POST],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun api(
        request: SabnzbdApiRequest
    ): ResponseEntity<String> = runBlocking {
        val resp = when (request.mode) {
            "version" -> """{"version": "4.4.0"}"""
            "get_config" -> config()
            "fullstatus" -> fullStatus()
            "addfile" -> Json.encodeToString(addNzbdFile(request))
            "queue" -> Json.encodeToString(sabNzbdService.queue(request))
            "history" -> Json.encodeToString(sabNzbdService.history(request))

            else -> {
                logger.error("unknown mode ${request.mode}")
                "else"
            }
        }
        ResponseEntity.ok(resp)
    }

    private suspend fun addNzbdFile(request: SabnzbdApiRequest): AddNzbResponse {
        val usenetDownload = sabNzbdService.addNzbFile(request)
        return AddNzbResponse(
            true,
            listOf(
                usenetDownload.id.toString()
            )
        )
    }

    private fun fullStatus(): String =
        resourceLoader.getResource("classpath:sabnzbd_fullstatus.json")
            .getContentAsString(Charsets.UTF_8)
            .replace("%MOUNT_PATH%", debridavConfigurationProperties.mountPath)
            .replace("%DOWNLOAD_PATH%", debridavConfigurationProperties.downloadPath)

    private fun config(): String =
        resourceLoader.getResource("classpath:sabnzbd_get_config_response.json")
            .getContentAsString(Charsets.UTF_8)
            .replace("%MOUNT_PATH%", debridavConfigurationProperties.mountPath)
            .replace("%DOWNLOAD_PATH%", debridavConfigurationProperties.downloadPath)
}
