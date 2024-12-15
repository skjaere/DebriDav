package io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb

data class SuccessfulAddNzbResponse(
    val downloadId: Long,
    val name: String,
    val hash: String
) : AddNzbResponse
