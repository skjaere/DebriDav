package io.skjaere.debridav.config

import org.springframework.core.env.MapPropertySource
import java.util.concurrent.ConcurrentHashMap

class DatabasePropertySource(
    private val map: ConcurrentHashMap<String, Any> = ConcurrentHashMap()
) : MapPropertySource(NAME, map) {

    fun replaceAll(overrides: Map<String, String>) {
        map.clear()
        map.putAll(overrides)
    }

    companion object {
        const val NAME = "databaseOverrides"
    }
}
