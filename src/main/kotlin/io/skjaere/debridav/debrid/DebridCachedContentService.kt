package io.skjaere.debridav.debrid


import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.model.ClientErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.GetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NetworkErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NotCachedGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.ProviderErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.SuccessfulGetCachedFilesResponse
import io.skjaere.debridav.debrid.model.ClientErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.GeneralErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.IsCachedResult
import io.skjaere.debridav.debrid.model.ProviderErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.SuccessfulIsCachedResult
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.UnknownError
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant


@Service
@Suppress("TooManyFunctions")
class DebridCachedContentService(
    private val debridClients: List<DebridCachedContentClient>,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(DebridCachedContentService::class.java)

    init {
        require(debridClients.isNotEmpty()) { "No debrid clients configured" }
    }

    suspend fun addContent(key: CachedContentKey): List<DebridFileContents> = coroutineScope {
        isCached(key).let { isCachedResponse ->
            if (isCachedResponse.all { it is SuccessfulIsCachedResult && !it.isCached }) {
                listOf()
            } else {
                getDebridProviderResponses(isCachedResponse, key)
                    .getDebridFileContents(key)
            }
        }
    }

    private suspend fun getDebridProviderResponses(
        isCachedResponse: List<IsCachedResult>,
        key: CachedContentKey
    ): GetCachedFilesResponses = coroutineScope {
        isCachedResponse
            .filter { !(it is SuccessfulIsCachedResult && !it.isCached) }
            .map { isCachedResult ->
                async {
                    getCachedFilesResponseWithRetries(
                        key,
                        debridClients.getClient(isCachedResult.debridProvider)
                    )
                }
            }
            .awaitAll()
            .plus(
                isCachedResponse
                    .filter { (it is SuccessfulIsCachedResult && !it.isCached) }
                    .map { NotCachedGetCachedFilesResponse(it.debridProvider) }
            )

    }

    @Suppress("TooGenericExceptionCaught")
    fun isCached(key: CachedContentKey): List<IsCachedResult> = runBlocking {
        debridavConfigurationProperties.debridClients
            .map { debridProvider ->
                async {
                    try {
                        SuccessfulIsCachedResult(
                            debridClients.getClient(debridProvider).isCached(key),
                            debridProvider
                        )
                    } catch (e: DebridError) {
                        mapIsCachedExceptionToError(key, debridProvider, e)
                    } catch (e: Exception) {
                        logger.error("Unknown error", e)
                        GeneralErrorIsCachedResponse(e, debridProvider)
                    }
                }
            }.awaitAll()
    }

    private fun mapIsCachedExceptionToError(
        key: CachedContentKey,
        debridProvider: DebridProvider,
        e: DebridError
    ): IsCachedResult {
        logger.error(
            "Received response: ${e.message} " +
                    "with status: ${e.statusCode} " +
                    "on endpoint: ${e.endpoint} " +
                    "while processing debridKey:$key"
        )
        return when (e) {
            is DebridClientError -> ClientErrorIsCachedResponse(e, debridProvider)

            is DebridProviderError -> ProviderErrorIsCachedResponse(e, debridProvider)

            is UnknownDebridError -> GeneralErrorIsCachedResponse(e, debridProvider)
        }
    }


    fun GetCachedFilesResponses.getDebridFileContents(magnet: CachedContentKey): List<DebridFileContents> =
        this
            .getDistinctFiles()
            .map { filePath ->
                debridavConfigurationProperties.debridClients.mapNotNull { provider ->
                    this.getResponseByFileWithPathAndProvider(filePath, provider)
                }
            }.map { cachedFiles ->
                createDebridFileContents(cachedFiles, magnet)
            }.toList()

    fun GetCachedFilesResponses.getDistinctFiles(): List<String> = this
        .flatMap { it.getCachedFiles() }
        .groupBy { cachedFile -> Pair(cachedFile.path!!.substringAfterLast('/'), cachedFile.size) }
        .map { (_, files) -> files.maxByOrNull { it.path!!.length }!! }
        .map { it.path!! }

    fun GetCachedFilesResponses.getResponseByFileWithPathAndProvider(
        path: String,
        debridProvider: DebridProvider
    ): DebridFile? {
        return when (val response = this.first { it.debridProvider == debridProvider }) {
            is NotCachedGetCachedFilesResponse -> MissingFile(debridProvider, Instant.now(clock).toEpochMilli())

            is ProviderErrorGetCachedFilesResponse -> ProviderError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )

            is NetworkErrorGetCachedFilesResponse -> NetworkError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )

            is SuccessfulGetCachedFilesResponse -> response.getCachedFiles()
                .firstOrNull { it.path!!.split("/").last() == path.split("/").last() }

            is ClientErrorGetCachedFilesResponse -> ClientError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )

            is UnknownError -> UnknownError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )
        }
    }

    private fun createDebridFileContents(
        cachedFiles: List<DebridFile>,
        key: CachedContentKey
    ): DebridFileContents {
        var contents: DebridFileContents? = null
        contents = when (key) {
            is UsenetRelease -> {
                DebridCachedUsenetReleaseContent(key.releaseName)
            }

            is TorrentMagnet -> {
                DebridCachedTorrentContent(key.magnet)
            }
        }
        contents.originalPath = getPathFromCachedFiles(cachedFiles)
        contents.size = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).size }
        contents.modified = Instant.now(clock).toEpochMilli()
        contents.debridLinks = cachedFiles.toMutableList()
        contents.mimeType = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).mimeType }
        return contents
    }

    private fun getPathFromCachedFiles(cachedFiles: List<DebridFile>): String =
        cachedFiles.filterIsInstance<CachedFile>()
            .groupBy { Pair(it.path!!.substringAfterLast("/"), it.size) }
            .values
            .flatten()
            .maxByOrNull { it.path!!.length }!!
            .path!!


    suspend fun getCachedFiles(
        key: CachedContentKey,
        debridClients: List<DebridCachedContentClient>
    ): Flow<GetCachedFilesResponse> = coroutineScope {
        debridClients
            .map { provider ->
                async { getCachedFilesResponseWithRetries(key, provider) }
            }.awaitAll()
            .asFlow()
    }

    private suspend fun getCachedFilesResponseWithRetries(
        key: CachedContentKey,
        debridClient: DebridCachedContentClient
    ) = tryGetCachedFiles(debridClient, key)
        .retry(debridavConfigurationProperties.retriesOnProviderError) { e ->
            (e.isRetryable()).also { if (it) delay(debridavConfigurationProperties.delayBetweenRetries.toMillis()) }
        }.catch { e ->
            logger.error("error getting cached files from ${debridClient.getProvider()}", e)
            when (e) {
                is DebridProviderError -> emit(ProviderErrorGetCachedFilesResponse(debridClient.getProvider()))
                is DebridClientError -> emit(ClientErrorGetCachedFilesResponse(debridClient.getProvider()))
                is UnknownDebridError -> emit(ProviderErrorGetCachedFilesResponse(debridClient.getProvider()))
                is IOException -> emit(NetworkErrorGetCachedFilesResponse(debridClient.getProvider()))
                else -> emit(NetworkErrorGetCachedFilesResponse(debridClient.getProvider()))
            }
        }.first()

    private fun Throwable.isRetryable(): Boolean {
        return when (this) {
            is IOException -> true
            is DebridProviderError -> true
            else -> false
        }
    }

    private suspend fun tryGetCachedFiles(
        debridClient: DebridCachedContentClient,
        key: CachedContentKey
    ): Flow<GetCachedFilesResponse> = flow {
        debridClient.getCachedFiles(key).let {
            if (it.isEmpty()) {
                emit(NotCachedGetCachedFilesResponse(debridClient.getProvider()))
            } else {
                emit(SuccessfulGetCachedFilesResponse(it, debridClient.getProvider()))
            }
        }
    }
}

fun List<DebridCachedContentClient>.getClient(debridProvider: DebridProvider): DebridCachedContentClient =
    this.first { it.getProvider() == debridProvider }

typealias GetCachedFilesResponses = List<GetCachedFilesResponse>
