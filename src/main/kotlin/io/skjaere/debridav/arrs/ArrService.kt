package io.skjaere.debridav.arrs

import io.skjaere.debridav.arrs.client.ArrClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ArrService(
    private val arrClients: List<ArrClient>,
) {
    private val logger = LoggerFactory.getLogger(ArrService::class.java)

    fun getClientForCategory(category: String): ArrClient? =
        arrClients.firstOrNull { it.getCategory() == category }

    suspend fun deleteFileAndSearch(itemName: String, category: String): Boolean {
        logger.info("Deleting file and triggering search for {} in Arrs", itemName)
        return getClientForCategory(category)?.let { client ->
            client.deleteFileAndSearch(itemName)
        } ?: false
    }

    suspend fun blocklist(downloadId: String, category: String) {
        getClientForCategory(category)?.blocklist(downloadId)
    }

    /**
     * Try `deleteFileAndSearch` against every configured arr and return true if any of them
     * recognised the item. Used by repair handlers when the source `category` is unknown —
     * typically because the [io.skjaere.debridav.torrent.Torrent] / [io.skjaere.debridav.usenet.UsenetDownload]
     * row was deleted before we kept the relationship around. Each arr's parse endpoint
     * (Sonarr's parse, Radarr's parse) returns "no match" for filenames that aren't its
     * domain, so non-matching arrs are silent no-ops.
     */
    suspend fun deleteFileAndSearchAcrossAllArrs(itemName: String): Boolean {
        if (arrClients.isEmpty()) return false
        logger.info("Fanning out deleteFileAndSearch for {} across {} arr(s)", itemName, arrClients.size)
        var anyHandled = false
        arrClients.forEach { client ->
            val handled = runCatching { client.deleteFileAndSearch(itemName) }
                .onFailure { e ->
                    logger.warn(
                        "Arr {} threw during fanout deleteFileAndSearch for {}: {}",
                        client.getCategory(), itemName, e.message,
                    )
                }
                .getOrDefault(false)
            if (handled) anyHandled = true
        }
        return anyHandled
    }

    /**
     * Send a blocklist to every configured arr. Arrs that don't have the download id in
     * their history return 404/no-op, so this is safe to fan out. Errors are logged and
     * swallowed to keep the repair flow resilient — a single arr being unhealthy must not
     * block repair across the others.
     */
    suspend fun blocklistAcrossAllArrs(downloadId: String) {
        if (arrClients.isEmpty()) return
        arrClients.forEach { client ->
            runCatching { client.blocklist(downloadId) }
                .onFailure { e ->
                    logger.warn(
                        "Arr {} threw during fanout blocklist for {}: {}",
                        client.getCategory(), downloadId, e.message,
                    )
                }
        }
    }
}
