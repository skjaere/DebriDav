package io.skjaere.debridav.usenet.sabnzbd

import com.fasterxml.jackson.annotation.JsonAlias

data class SabnzbdApiRequest(
    val mode: String?,
    val cat: String?,
    val name: Any?,
    /**
     * SABnzbd shared-secret auth. Sonarr / Radarr send it as `apikey=` on every request.
     * Validated in [SabnzbdApiController.api] against `debridav.sabnzbd-api-key`.
     */
    val apikey: String? = null,
    /**
     * Alternate field name for the multipart-uploaded NZB file. SABnzbd's public
     * API accepts the file under `name`, but Sportarr's `AddNzbViaContentAsync`
     * sends it as `nzbfile` instead. We accept either and prefer whichever is
     * non-null in [SabNzbdService.addNzbFile].
     */
    val nzbfile: Any? = null,
    val value: String?,
    @JsonAlias("del_files") val delFiles: Int?,
    /**
     * Optional release-name override sent by Sonarr/Radarr alongside `mode=addurl`.
     * SABnzbd uses it to override the release-name auto-detection from the URL
     * path. Null for `mode=addfile` (the multipart upload's filename is used).
     */
    val nzbname: String? = null,
)
