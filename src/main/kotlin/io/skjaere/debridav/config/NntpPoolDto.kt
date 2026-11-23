package io.skjaere.debridav.config

data class NntpPoolDto(
    val host: String = "",
    val port: Int = 563,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = true,
    val maxConnections: Int = 8,
    val priority: Int = 0
)
