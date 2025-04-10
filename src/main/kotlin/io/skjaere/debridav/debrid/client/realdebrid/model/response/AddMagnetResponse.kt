package io.skjaere.debridav.debrid.client.realdebrid.model.response

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
sealed interface AddMagnetResponse

@Serializable
data class SuccessfulAddMagnetResponse(
    val id: String,
    val uri: String
) : AddMagnetResponse

@Serializable
data class FailedAddMagnetResponse(
    val reason: String,
) : AddMagnetResponse

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RealDebridErrorMessage(
    val error: String,
    @JsonNames("error_code") val errorCode: Int,
)


