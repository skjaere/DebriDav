package io.skjaere.debridav.usenet.sabnzbd

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
        // Two accepted auth modes:
        //   1. SABnzbd-native: caller supplies `apikey=` matching `debridav.sabnzbd-api-key`.
        //      This is what Sonarr/Radarr send.
        //   2. Bearer JWT: a logged-in UI session reaches the endpoint via the same path
        //      (the bundled frontend's "add NZB" form posts to `/api`). JwtAuthenticationFilter
        //      has already validated the token and populated SecurityContextHolder by the time
        //      we get here, so we can just check whether a non-anonymous authentication is set.
        //
        // Either path is sufficient — without one of them, return SABnzbd's standard
        // "API Key Incorrect" error body so Sonarr/Radarr surface a useful message.
        val authentication = SecurityContextHolder.getContext().authentication
        val jwtAuthenticated = authentication != null
            && authentication.isAuthenticated
            && authentication !is AnonymousAuthenticationToken
        val configuredKey = debridavConfigurationProperties.sabnzbdApiKey
        val apikeyAccepted = configuredKey.isNotBlank() && request.apikey == configuredKey
        if (!jwtAuthenticated && !apikeyAccepted) {
            return@runBlocking ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("""{"status": false, "error": "API Key Incorrect"}""")
        }
        val resp = when (request.mode) {
            "version" -> """{"version": "4.4.0"}"""
            "get_config" -> sabNzbdService.config()
            "fullstatus" -> fullStatus()
            "addfile" -> Json.encodeToString(addNzbdFile(request))
            "addurl" -> Json.encodeToString(addNzbdUrl(request))
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

    private suspend fun addNzbdUrl(request: SabnzbdApiRequest): AddNzbResponse {
        val usenetDownload = sabNzbdService.addNzbUrl(request)
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

}
