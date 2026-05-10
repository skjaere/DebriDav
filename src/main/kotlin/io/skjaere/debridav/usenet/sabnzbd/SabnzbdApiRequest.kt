package io.skjaere.debridav.usenet.sabnzbd

import com.fasterxml.jackson.annotation.JsonAlias

data class SabnzbdApiRequest(
    val mode: String?,
    val cat: String?,
    val name: Any?,
    val value: String?,
    @JsonAlias("del_files") val delFiles: Int?,
    /**
     * Optional release-name override sent by Sonarr/Radarr alongside `mode=addurl`.
     * SABnzbd uses it to override the release-name auto-detection from the URL
     * path. Null for `mode=addfile` (the multipart upload's filename is used).
     */
    val nzbname: String? = null,
)
