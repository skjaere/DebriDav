package io.skjaere.debridav.sonarr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service

@Service
class SonarrService(
    private val sonarrClient: SonarrClient,
    private val threadPoolTaskExecutor: ThreadPoolTaskExecutor,
) {
    private val logger = LoggerFactory.getLogger(SonarrService::class.java)
    private val torrentNotCachedChannel = Channel<String>()
    private final val queueProcessorScope = CoroutineScope(Dispatchers.IO)

    /*    init {
            queueProcessorScope.launch {
                downloadFailedPipeline()
            }
        }*/

    suspend fun markDownloadAsFailed(torrentName: String) {
        logger.info("Marking download of $torrentName as failed in Sonarr")
        threadPoolTaskExecutor.submit {
            runBlocking {
                val episodeId = getEpisodeIdWhenPresent(torrentName)
                val historyId = getHistoryIdFromEpisodeId(episodeId)
                markAsFailed(historyId)
            }
        }
    }

    /*    private suspend fun downloadFailedPipeline() = coroutineScope {
            launch {
                torrentNotCachedChannel.consumeEach { torrentName ->

                }
            }

        }*/

    private suspend fun markAsFailed(channel: ReceiveChannel<Long>) {
        channel.consumeEach { historyId -> sonarrClient.failed(historyId) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.getHistoryIdFromEpisodeId(channel: ReceiveChannel<Long>) = produce {
        channel.consumeEach { episodeId ->
            val historyId = sonarrClient.history(episodeId).records
                .first { it.eventType == "grabbed" }
                .id
            send(historyId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun CoroutineScope.getEpisodeIdWhenPresent(torrentName: String) = produce {
        var parsed = sonarrClient.parse(torrentName)
        if (parsed.episodes.isEmpty()) {
            do {
                delay(2000)
                parsed = sonarrClient.parse(torrentName)
            } while (parsed.episodes.isEmpty())
        }
        send(parsed.episodes.first().id)
    }
}