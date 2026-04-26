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
}
