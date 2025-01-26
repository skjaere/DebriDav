package io.skjaere.debridav.usenet.sabnzbd

import com.fasterxml.jackson.annotation.JsonAlias

data class SabnzbdApiRequest(
    val mode: String?,
    val cat: String?,
    val name: Any?,
    val value: String?,
    @JsonAlias("del_files") val delFiles: Int?
)
