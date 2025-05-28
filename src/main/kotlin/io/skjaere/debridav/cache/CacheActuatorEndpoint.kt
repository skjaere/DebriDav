package io.skjaere.debridav.cache


import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.stereotype.Component

@Component
@Endpoint(id = "cache")
class CacheActuatorEndpoint(
    private val fileChunkCachingService: FileChunkCachingService
) {
    @DeleteOperation
    fun purgeCache() {
        fileChunkCachingService.purgeCache()
    }

    /*    @DeleteOperation
        fun purgeCacheForFileAtPath(path: String) {
            fileChunkCachingService.purgeCache()
        }*/
}
