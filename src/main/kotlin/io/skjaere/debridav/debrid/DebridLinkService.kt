package io.skjaere.debridav.debrid

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.model.ClientErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.GetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NetworkErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NotCachedGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.ProviderErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.SuccessfulGetCachedFilesResponse
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import io.skjaere.debridav.fs.UnknownDebridLinkError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

const val RETRIES = 3L

private const val CACHE_GRACE_PERIOD_SECONDS = 5L

private const val CACHE_SIZE = 100L

@Service
@Suppress("LongParameterList")
class DebridLinkService(
    private val debridCachedContentService: DebridCachedContentService,
    private val fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val debridClients: List<DebridCachedContentClient>,
    private val clock: Clock,
    meterRegistry: MeterRegistry
) {

    private val logger = LoggerFactory.getLogger(DebridLinkService::class.java)

    data class LinkLivenessCacheKey(val provider: String, val cachedFile: CachedFile)

    val isLinkAliveCache: LoadingCache<LinkLivenessCacheKey, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(CACHE_GRACE_PERIOD_SECONDS))
        .maximumSize(CACHE_SIZE)
        .build(CacheLoader<LinkLivenessCacheKey, Boolean> { key ->
            runBlocking {
                logger.info("Checking if link is alive for ${key.cachedFile.provider} ${key.cachedFile.path}")
                debridClients
                    .firstOrNull { it.getProvider().toString() == key.provider }?.isLinkAlive(key.cachedFile)
                    ?: false
            }
        })
    val cachedFileCache: LoadingCache<RemotelyCachedEntity, CachedFile?> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(CACHE_GRACE_PERIOD_SECONDS))
        .maximumSize(CACHE_SIZE)
        .build(CacheLoader<RemotelyCachedEntity, CachedFile?> { entity ->
            runBlocking {
                getCachedFile(entity)
            }
        })

    @Suppress("MagicNumber")
    private val linkFindingDurationSummary = DistributionSummary
        .builder("debridav.streaming.find.working.link.duration.summary")
        .serviceLevelObjectives(
            1.0,
            25.0,
            50.0,
            75.0,
            100.0,
            125.0,
            150.0,
            250.0,
            350.0,
            500.0,
            1000.0,
            1500.0,
            2000.0,
            3000.0,
            5000.0,
            10_000.0
        )
        .register(meterRegistry)

    suspend fun getCachedFileCached(file: RemotelyCachedEntity): CachedFile? = cachedFileCache.get(file)

    suspend fun getCachedFile(file: RemotelyCachedEntity): CachedFile? = getCheckedLinks(file).firstOrNull()
    suspend fun getCheckedLinks(file: RemotelyCachedEntity): Flow<CachedFile> {
        val debridFileContents = file.contents!!
        val started = Instant.now()
        logger.info("Getting links for ${file.name} from ${debridFileContents.originalPath}")
        return getFlowOfDebridLinks(debridFileContents)
            .retry(RETRIES)
            .catch { e ->
                logger.error("Uncaught exception encountered while getting links", e)
            }
            .transformWhile { debridLink ->
                if (debridLink !is NetworkError) {
                    updateContentsOfDebridFile(file, debridFileContents, debridLink)
                }
                if (debridLink is CachedFile) {
                    val took = Duration.between(started, Instant.now()).toMillis().toDouble()
                    logger.info("Found link for ${file.name} from ${debridLink.provider}. took $took ms")
                    linkFindingDurationSummary.record(took)
                    emit(debridLink)
                } else {
                    logger.info(
                        "result was ${debridLink.javaClass.simpleName} " +
                                "for ${file.name} from ${debridLink.provider}"
                    )
                }
                debridLink !is CachedFile
            }
    }

    private fun mapExceptionToDebridFile(e: DebridError, provider: DebridProvider): DebridFile {
        return when (e) {
            is DebridClientError -> ClientError(provider, Instant.now().toEpochMilli())
            is DebridProviderError -> ProviderError(provider, Instant.now().toEpochMilli())
            is UnknownDebridError -> UnknownDebridLinkError(
                provider,
                Instant.now().toEpochMilli()
            )
        }
    }

    private suspend fun getFlowOfDebridLinks(debridFileContents: DebridFileContents): Flow<DebridFile> = flow {
        debridavConfigurationProperties.debridClients
            .map { debridClients.getClient(it) }
            .map { debridClient ->
                debridFileContents.debridLinks
                    .firstOrNull { it.provider == debridClient.getProvider() }
                    ?.let { debridFile ->
                        emitDebridFile(
                            debridFile,
                            debridFileContents,
                            debridClient.getProvider()
                        )
                    } ?: run { emit(getFreshDebridLink(debridFileContents, debridClient)) }
            }
    }

    private suspend fun getFreshDebridLink(
        debridFileContents: DebridFileContents,
        debridClient: DebridCachedContentClient
    ): DebridFile {
        val key = when (debridFileContents) {
            is DebridCachedTorrentContent -> TorrentMagnet(debridFileContents.magnet!!)
            is DebridCachedUsenetReleaseContent -> UsenetRelease(debridFileContents.releaseName!!)
            else -> error("Unknown DebridFileContents: ${debridFileContents.javaClass.simpleName}")
        }
        return debridFileContents.debridLinks
            .firstOrNull { it.provider == debridClient.getProvider() }
            ?.let { debridFile ->
                getDebridLinkFromDebridFile(debridFile, debridClient, key)
            } ?: run {
            if (debridClient.isCached(key)) {
                return debridCachedContentService.getCachedFiles(key, listOf(debridClient))
                    .map { response ->
                        mapResponseToDebridFile(response, debridFileContents, debridClient)
                    }.first()
            } else {
                MissingFile(debridClient.getProvider(), clock.instant().toEpochMilli())
            }
        }
    }

    private suspend fun getDebridLinkFromDebridFile(
        debridFile: DebridFile,
        debridClient: DebridCachedContentClient,
        key: CachedContentKey
    ): DebridFile? {
        return if (debridFile is CachedFile) {
            try {
                debridClient.getStreamableLink(key, debridFile)
                    ?.let { link ->
                        debridFile.link = link
                        debridFile
                    }
            } catch (e: DebridError) {
                logger.error("Uncaught exception encountered while getting link", e)
                mapExceptionToDebridFile(e, debridFile.provider!!)
            }
        } else null
    }


    private fun mapResponseToDebridFile(
        response: GetCachedFilesResponse,
        debridFileContents: DebridFileContents,
        debridClient: DebridCachedContentClient
    ): DebridFile {
        return when (response) {
            is SuccessfulGetCachedFilesResponse -> {
                if (response.getCachedFiles().size == 1) {
                    response.getCachedFiles().first()
                } else {
                    response.getCachedFiles()
                        .firstOrNull { fileMatches(it, debridFileContents) }
                        ?: run {
                            logger.warn(
                                "Could not match any file in response ${response.getCachedFiles()} " +
                                        "from ${response.debridProvider} to ${debridFileContents.originalPath}"
                            )
                            MissingFile(debridClient.getProvider(), clock.instant().toEpochMilli())
                        }
                }
            }

            is ProviderErrorGetCachedFilesResponse -> ProviderError(
                debridClient.getProvider(),
                clock.instant().toEpochMilli()
            )

            is NotCachedGetCachedFilesResponse -> MissingFile(
                debridClient.getProvider(),
                clock.instant().toEpochMilli()
            )

            is NetworkErrorGetCachedFilesResponse -> NetworkError(
                debridClient.getProvider(),
                clock.instant().toEpochMilli()
            )

            is ClientErrorGetCachedFilesResponse -> ClientError(
                debridClient.getProvider(),
                clock.instant().toEpochMilli()
            )
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitDebridFile(
        debridFile: DebridFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {
        when (debridFile) {
            is CachedFile -> emitWorkingLink(debridFile, debridFileContents, debridProvider)
            is MissingFile -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is ProviderError -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is ClientError -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is NetworkError -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)

            is UnknownDebridLinkError -> emitRefreshedResult(
                debridFile,
                debridFileContents,
                debridProvider
            )
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitRefreshedResult(
        debridFile: DebridFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {
        if (linkShouldBeReChecked(debridFile)) {
            emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider)))
        } else {
            emit(debridFile)
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitWorkingLink(
        debridFile: CachedFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {

        /*if (debridClients
                .first { it.getProvider() == debridProvider }
                .isLinkAlive(debridFile)
        )*/
        if (isLinkAliveCache.get(
                LinkLivenessCacheKey(debridProvider.toString(), debridFile)
            )
        ) {
            emit(debridFile)
        } else {
            emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider)))
        }
    }

    private fun updateContentsOfDebridFile(
        file: RemotelyCachedEntity,
        debridFileContents: DebridFileContents,
        debridLink: DebridFile
    ) {
        debridFileContents.replaceOrAddDebridLink(debridLink)
        fileService.writeDebridFileContentsToFile(file, debridFileContents)
    }

    private fun fileMatches(
        it: CachedFile,
        debridFileContents: DebridFileContents
    ) = it.path!!.normalize().split("/").last() == debridFileContents.originalPath!!.normalize().split("/").last()
            || it.size == debridFileContents.size!!
            || debridFileContents.originalPath!!.contains(it.path!!)
            || it.path!!.contains(debridFileContents.originalPath!!)

    fun String.normalize() = this.replace(" ", "").replace(".", "")

    private fun linkShouldBeReChecked(debridFile: DebridFile): Boolean {
        return when (debridFile) {
            is MissingFile -> debridavConfigurationProperties.waitAfterMissing
            is ProviderError -> debridavConfigurationProperties.waitAfterProviderError
            is NetworkError -> debridavConfigurationProperties.waitAfterNetworkError
            is ClientError -> debridavConfigurationProperties.waitAfterClientError
            is UnknownDebridLinkError -> debridavConfigurationProperties.waitAfterNetworkError
            is CachedFile -> error("should never happen")
            else -> error("Unknown type ${debridFile.javaClass.simpleName}")
        }.let {
            Instant.ofEpochMilli(debridFile.lastChecked!!)
                .isBefore(clock.instant().minus(it))
        }
    }

    fun List<DebridCachedContentClient>.getClient(debridProvider: DebridProvider): DebridCachedContentClient =
        this.first { it.getProvider() == debridProvider }

}
