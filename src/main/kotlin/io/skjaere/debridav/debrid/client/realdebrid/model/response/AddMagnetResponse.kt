package io.skjaere.debridav.debrid.client.realdebrid.model.response

import kotlinx.serialization.Serializable


@Serializable
data class AddMagnetResponse(
    val id: String,
    val uri: String
)
