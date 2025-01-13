package io.skjaere.debridav.arrs.client

interface ArrClient: BaseArrClient {
    suspend fun getItemIdFromName(name: String): Long?
    fun getCategory(): String
}
