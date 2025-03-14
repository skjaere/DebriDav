package io.skjaere.debridav.arrs


import io.skjaere.debridav.arrs.client.ArrClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.time.Instant


private const val DELAY_TIME = 2000L
private const val ITEM_PRESENCE_IN_ARR_TIMEOUT_SECONDS = 60L

@Service
class ArrService(
    private val arrClients: List<ArrClient>,
    private val threadPoolTaskExecutor: ThreadPoolTaskExecutor,
) {
    private val logger = LoggerFactory.getLogger(ArrService::class.java)

    suspend fun markDownloadAsFailed(itemName: String, category: String) {
        logger.info("Marking download of $itemName as failed in Arrs")
        threadPoolTaskExecutor.submit {
            runBlocking { downloadFailedPipeline(category, itemName) }
        }
    }

    fun categoryIsMapped(category: String): Boolean {
        return arrClients
            .firstOrNull { it.getCategory() == category } != null
    }

    suspend fun downloadFailedPipeline(
        category: String,
        itemName: String
    ) = coroutineScope {
        arrClients.getClientForCategory(category)?.let { client ->
            markAsFailed(
                getHistoryIdFromEpisodeId(
                    getItemIdWhenPresent(
                        MarkDownloadAsFailedContext(client, itemName)
                    )
                )
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun markAsFailed(channel: ReceiveChannel<MarkDownloadAsFailedContext>) {
        channel.consumeEach { context ->
            try {
                context.client.failed(context.historyId!!)
            } catch (e: Exception) {
                logger.error("Error marking item as failed: ${context.itemName}", e)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.getHistoryIdFromEpisodeId(channel: ReceiveChannel<MarkDownloadAsFailedContext>) =
        produce {
            channel.consumeEach { context ->
                try {
                    val historyId = context.client.history(context.itemId!!).records
                        .first { it.eventType == "grabbed" }
                        .id
                    send(context.copy(historyId = historyId))
                } catch (e: Exception) {
                    logger.error("Error getting history for item: ${context.itemName}", e)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun CoroutineScope.getItemIdWhenPresent(context: MarkDownloadAsFailedContext) = this.produce {
        try {
            var itemId = context.client.getItemIdFromName(context.itemName!!)
            if (itemId == null) {
                do {
                    delay(DELAY_TIME)
                    itemId = context.client.getItemIdFromName(context.itemName)
                } while (itemId == null && context.created.isBefore(Instant.now().plusSeconds(
                        ITEM_PRESENCE_IN_ARR_TIMEOUT_SECONDS
                    )))
            }
            if (itemId == null) {
                logger.warn("Could not find item: ${context.itemName} in arrs within 60 seconds")
            } else {
                send(context.copy(itemId = itemId))
            }
        } catch (e: Exception) {
            logger.error("Error getting item id: ${context.itemName}", e)
        }
    }

    private fun List<ArrClient>.getClientForCategory(category: String): ArrClient? =
        this.firstOrNull { it.getCategory() == category }

    data class MarkDownloadAsFailedContext(
        val client: ArrClient,
        val itemName: String?,
        val itemId: Long?,
        val historyId: Long?,
        val created: Instant
    ) {
        constructor(client: ArrClient, itemName: String) : this(client, itemName, null, null, Instant.now())
    }
}
