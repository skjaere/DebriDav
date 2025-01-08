package io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb

import kotlinx.serialization.Serializable

@Serializable
data class ServiceErrorAddNzbResponse(val error: String) : AddNzbResponse
