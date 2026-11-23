package io.skjaere.debridav.arrs

import io.skjaere.debridav.arrs.client.ArrClient
import io.skjaere.debridav.arrs.client.DeleteAndSearchResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ArrService(
    private val arrClients: List<ArrClient>,
) {
    private val logger = LoggerFactory.getLogger(ArrService::class.java)

    fun getClientForCategory(category: String): ArrClient? =
        arrClients.firstOrNull { it.getCategory() == category }

    suspend fun deleteFileAndSearch(
        itemName: String,
        expectedPaths: Set<String>,
        category: String,
    ): DeleteAndSearchResult {
        logger.info(
            "Deleting file and triggering search for {} in Arrs (expected paths: {})",
            itemName, expectedPaths,
        )
        return getClientForCategory(category)?.deleteFileAndSearch(itemName, expectedPaths)
            ?: DeleteAndSearchResult.NotImported
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
     * for the others to do. Otherwise an arr that has the release imported elsewhere wins over
     * one that couldn't be asked, which wins over "not imported": only when every arr said it
     * hasn't imported the release is the file safe to delete.
     */
    @Suppress("ReturnCount")
    suspend fun deleteFileAndSearchAcrossAllArrs(itemName: String, expectedPaths: Set<String>): DeleteAndSearchResult {
        if (arrClients.isEmpty()) return DeleteAndSearchResult.NotImported
        logger.info(
            "Fanning out deleteFileAndSearch for {} (expected paths: {}) across {} arr(s)",
            itemName, expectedPaths, arrClients.size,
        )
        val results = mutableListOf<DeleteAndSearchResult>()
        for (client in arrClients) {
            val result = runCatching { client.deleteFileAndSearch(itemName, expectedPaths) }
                .getOrElse { e ->
                    logger.warn(
                        "Arr {} threw during fanout deleteFileAndSearch for {}: {}",
                        client.getCategory(), itemName, e.message,
                    )
                    DeleteAndSearchResult.Failed(e.message)
                }
            if (result == DeleteAndSearchResult.Replaced) return result
            results += result
        }
        val elsewhere = results.filterIsInstance<DeleteAndSearchResult.ImportedElsewhere>()
        return when {
            elsewhere.isNotEmpty() -> DeleteAndSearchResult.ImportedElsewhere(elsewhere.flatMap { it.arrPaths })
            else -> results.firstOrNull { it is DeleteAndSearchResult.Failed } ?: DeleteAndSearchResult.NotImported
        }
    }

    /**
     * Try to blocklist the [downloadId] against every configured arr in turn and stop as
     * soon as one of them reports success ([BaseArrClient.blocklist] returns true when the
     * arr had a matching history record and marked it failed). Arrs that don't recognise
     * the download return false and we move on; per-arr exceptions are logged and ignored
     * so a single unhealthy arr can't block the others.
     */
    /**
     * Nudge the arr matching [category] to scan for newly-completed downloads
     * immediately (skips its polling delay). Fire-and-forget at the call site —
     * exceptions are swallowed; a refresh failure shouldn't disrupt the import path
     * that triggered it. No fanout: if [category] doesn't map to any configured
     * arr the call is a no-op (the content wasn't added by an arr in the first
     * place, so there's nothing to nudge).
     */
    suspend fun refreshMonitoredDownloads(category: String) {
        getClientForCategory(category)?.let { client ->
            runCatching { client.refreshMonitoredDownloads() }
                .onFailure { e ->
                    logger.warn(
                        "Arr {} threw during refreshMonitoredDownloads: {}",
                        client.getCategory(), e.message,
                    )
                }
        } ?: logger.debug("No arr client for category {} — skipping refresh", category)
    }

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
