package io.skjaere.debridav.debrid.client.premiumize.model

import kotlinx.serialization.Serializable

@Serializable
class UnsuccessfulDirectDownloadResponse(override val status: String) : DirectDownloadResponse
