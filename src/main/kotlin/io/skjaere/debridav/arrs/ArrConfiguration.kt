package io.skjaere.debridav.arrs

interface ArrConfiguration {
    val host: String
    val port: Int
    val apiBasePath: String
    val apiKey: String
    val category: String
    val integrationEnabled: Boolean

    fun getApiBaseUrl(): String = "http://$host:$port$apiBasePath"
}
