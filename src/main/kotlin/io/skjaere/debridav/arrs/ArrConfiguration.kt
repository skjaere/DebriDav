package io.skjaere.debridav.arrs

interface ArrConfiguration {
    var host: String
    var port: Int
    var apiBasePath: String
    var apiKey: String
    var category: String
    var integrationEnabled: Boolean

    fun getApiBaseUrl(): String = "http://$host:$port$apiBasePath"
}
