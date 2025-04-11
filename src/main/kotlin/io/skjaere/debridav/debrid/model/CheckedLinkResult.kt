package io.skjaere.debridav.debrid.model

data class CheckedLinkResult(
    val debridFile: DebridFile,
    val link: String?,
)
