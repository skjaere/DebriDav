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

    suspend fun deleteFileAndSearch(itemName: String, expectedPaths: Set<String>, category: String): Boolean {
        logger.info(
            "Deleting file and triggering search for {} in Arrs (expected paths: {})",
            itemName, expectedPaths,
        )
        return getClientForCategory(category)?.let { client ->
            client.deleteFileAndSearch(itemName, expectedPaths)
        } ?: false
    }

    suspend fun blocklist(downloadId: String, category: String) {
        getClientForCategory(category)?.blocklist(downloadId)
    }

    /**
     * Try `deleteFileAndSearch` against every configured arr in turn and return true as soon
     * as any of them recognises the item. Used by repair handlers when the source `category`
     * is unknown — typically because the [io.skjaere.debridav.torrent.Torrent] /
     * [io.skjaere.debridav.usenet.UsenetDownload] row was deleted before we kept the
     * relationship around. Each arr's parse endpoint (Sonarr's parse, Radarr's parse) returns
     * "no match" for filenames that aren't its domain, so non-matching arrs are silent no-ops.
     *
     * Short-circuits on the first match — once one arr has handled the file there's nothing
     * for the others to do.
     */
    suspend fun deleteFileAndSearchAcrossAllArrs(itemName: String, expectedPaths: Set<String>): Boolean {
        if (arrClients.isEmpty()) return false
        logger.info(
            "Fanning out deleteFileAndSearch for {} (expected paths: {}) across {} arr(s)",
            itemName, expectedPaths, arrClients.size,
        )
        return arrClients.any { client ->
            runCatching { client.deleteFileAndSearch(itemName, expectedPaths) }
                .onFailure { e ->
                    logger.warn(
                        "Arr {} threw during fanout deleteFileAndSearch for {}: {}",
                        client.getCategory(), itemName, e.message,
                    )
                }
                .getOrDefault(false)
        }
    }

    /**
     * Try to blocklist the [downloadId] against every configured arr in turn and stop as
     * soon as one of them reports success ([BaseArrClient.blocklist] returns true when the
     * arr had a matching history record and marked it failed). Arrs that don't recognise
     * the download return false and we move on; per-arr exceptions are logged and ignored
     * so a single unhealthy arr can't block the others.
     */
    suspend fun blocklistAcrossAllArrs(downloadId: String): Boolean {
        if (arrClients.isEmpty()) return false
        return arrClients.any { client ->
            runCatching { client.blocklist(downloadId) }
                .onFailure { e ->
                    logger.warn(
                        "Arr {} threw during fanout blocklist for {}: {}",
                        client.getCategory(), downloadId, e.message,
                    )
                }
                .getOrDefault(false)
        }
    }
}
