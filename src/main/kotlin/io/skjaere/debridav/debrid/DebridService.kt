package io.skjaere.debridav.debrid


import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.LinkCheckService
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.model.ClientErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.GetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NetworkErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NotCachedGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.ProviderErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.SuccessfulGetCachedFilesResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.ClientErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.GeneralErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.IsCachedResult
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.ProviderErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.SuccessfulIsCachedResult
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.qbittorrent.TorrentService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.time.Clock
import java.time.Instant


@Service
@Suppress("TooManyFunctions")
class DebridService(
    private val debridClients: List<DebridClient>,
    private val debridavConfiguration: DebridavConfiguration,
    private val fileService: FileService,
    private val linkCheckService: LinkCheckService,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(DebridService::class.java)

    suspend fun addMagnet(magnet: String): List<DebridFileContents> = coroutineScope {
        isCached(magnet).let { isCachedResponse ->
            if (isCachedResponse.all { it is SuccessfulIsCachedResult && !it.isCached }) {
                listOf()
            } else {
                getDebridProviderResponses(isCachedResponse, magnet)
                    .getDebridFileContents(magnet)
            }
        }
    }

    private suspend fun getDebridProviderResponses(
        isCachedResponse: List<IsCachedResult>,
        magnet: String
    ): GetCachedFilesResponses = coroutineScope {
        isCachedResponse
            .filter { !(it is SuccessfulIsCachedResult && !it.isCached) }
            .map { isCachedResult ->
                async {
                    getCachedFilesResponseWithRetries(
                        magnet,
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
    fun isCached(magnet: String): List<IsCachedResult> = runBlocking {
        debridavConfiguration.debridClients
            .map { debridProvider ->
                async {
                    try {
                        SuccessfulIsCachedResult(
                            debridClients.getClient(debridProvider).isCached(magnet),
                            debridProvider
                        )
                    } catch (e: DebridError) {
                        mapIsCachedExceptionToError(magnet, debridProvider, e)
                    } catch (e: Exception) {
                        logger.error("Unknown error", e)
                        GeneralErrorIsCachedResponse(e, debridProvider)
                    }
                }
            }.awaitAll()
    }

    private fun mapIsCachedExceptionToError(
        magnet: String,
        debridProvider: DebridProvider,
        e: DebridError
    ): IsCachedResult {
        val magnetName = TorrentService.getNameFromMagnet(magnet)
        logger.error(
            "Received response: ${e.message} " +
                    "with status: ${e.statusCode} " +
                    "on endpoint: ${e.endpoint} " +
                    "while processing torrent:$magnetName"
        )
        return when (e) {
            is DebridClientError -> ClientErrorIsCachedResponse(e, debridProvider)

            is DebridProviderError -> ProviderErrorIsCachedResponse(e, debridProvider)

            is UnknownDebridError -> GeneralErrorIsCachedResponse(e, debridProvider)
        }
    }


    suspend fun getCheckedLinks(file: File): Flow<CachedFile> {
        val debridFileContents = fileService.getDebridFileContents(file)
        return getFlowOfDebridLinks(debridFileContents)
            .catch { e -> logger.error("Uncaught exception encountered while getting links", e) }
            .transformWhile { debridLink ->
                if (debridLink !is NetworkError) {
                    updateContentsOfDebridFile(debridFileContents, debridLink, file)
                }
                if (debridLink is CachedFile) {
                    emit(debridLink)
                }
                debridLink !is CachedFile
            }
    }

    fun GetCachedFilesResponses.getDebridFileContents(magnet: String): List<DebridFileContents> = this
        .getDistinctFiles()
        .map { filePath ->
            debridavConfiguration.debridClients.mapNotNull { provider ->
                this.getResponseByFileWithPathAndProvider(filePath, provider)
            }
        }.map { cachedFiles ->
            createDebridFileContents(cachedFiles, magnet)
        }.toList()

    fun GetCachedFilesResponses.getDistinctFiles(): List<String> = this
        .flatMap { it.getCachedFiles() }
        .map { it.path }
        .distinct()

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
                .firstOrNull { it.path.split("/").last() == path.split("/").last() }

            is ClientErrorGetCachedFilesResponse -> ClientError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )
        }
    }

    private fun createDebridFileContents(
        cachedFiles: List<DebridFile>,
        magnet: String
    ) = DebridFileContents(
        originalPath = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).path },
        size = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).size },
        modified = Instant.now(clock).toEpochMilli(),
        magnet = magnet,
        debridLinks = cachedFiles.toMutableList()
    )

    suspend fun getCachedFiles(
        magnet: String,
        debridClients: List<DebridClient>
    ): Flow<GetCachedFilesResponse> = coroutineScope {
        debridClients
            .map { provider ->
                async { getCachedFilesResponseWithRetries(magnet, provider) }
            }.awaitAll()
            .asFlow()
    }

    private suspend fun getCachedFilesResponseWithRetries(
        magnet: String,
        debridClient: DebridClient
    ) = tryGetCachedFiles(debridClient, magnet)
        .retry(debridavConfiguration.retriesOnProviderError) { e ->
            (e.isRetryable()).also { if (it) delay(debridavConfiguration.delayBetweenRetries.toMillis()) }
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
        debridClient: DebridClient,
        magnet: String
    ): Flow<GetCachedFilesResponse> = flow {
        debridClient.getCachedFiles(magnet).let {
            if (it.isEmpty()) {
                emit(NotCachedGetCachedFilesResponse(debridClient.getProvider()))
            } else {
                emit(SuccessfulGetCachedFilesResponse(it, debridClient.getProvider()))
            }
        }
    }


    private fun updateContentsOfDebridFile(
        debridFileContents: DebridFileContents,
        debridLink: DebridFile,
        file: File
    ) {
        debridFileContents.replaceOrAddDebridLink(debridLink)
        fileService.writeContentsToFile(file, debridFileContents)
    }

    private suspend fun getFlowOfDebridLinks(debridFileContents: DebridFileContents): Flow<DebridFile> = flow {
        debridavConfiguration.debridClients.map { debridProvider ->
            debridFileContents.debridLinks
                .firstOrNull { it.provider == debridProvider }
                ?.let { debridFile -> emitDebridFile(debridFile, debridFileContents, debridProvider) }
                ?: run { emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider))) }
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
            is NetworkError -> emit(
                NetworkError(
                    debridProvider,
                    Instant.now(clock).toEpochMilli()
                )
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
        if (linkCheckService.isLinkAlive(debridFile)) {
            emit(debridFile)
        } else {
            emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider)))
        }
    }

    private suspend fun getFreshDebridLink(
        debridFileContents: DebridFileContents,
        debridClient: DebridClient
    ): DebridFile {
        return if (debridClient.isCached(debridFileContents.magnet)) {
            return getCachedFiles(debridFileContents.magnet, listOf(debridClient))
                .map { response ->
                    mapResponseToDebridFile(response, debridFileContents, debridClient)
                }.first()
        } else {
            MissingFile(debridClient.getProvider(), Instant.now(clock).toEpochMilli())
        }
    }

    private fun mapResponseToDebridFile(
        response: GetCachedFilesResponse,
        debridFileContents: DebridFileContents,
        debridClient: DebridClient
    ) = when (response) {
        is SuccessfulGetCachedFilesResponse -> response.getCachedFiles()
            .first { fileMatches(it, debridFileContents) }

        is ProviderErrorGetCachedFilesResponse -> ProviderError(
            debridClient.getProvider(),
            Instant.now(clock).toEpochMilli()
        )

        is NotCachedGetCachedFilesResponse -> MissingFile(
            debridClient.getProvider(),
            Instant.now(clock).toEpochMilli()
        )

        is NetworkErrorGetCachedFilesResponse -> NetworkError(
            debridClient.getProvider(),
            Instant.now(clock).toEpochMilli()
        )

        is ClientErrorGetCachedFilesResponse -> ClientError(
            debridClient.getProvider(),
            Instant.now(clock).toEpochMilli(),
        )
    }

    private fun fileMatches(
        it: CachedFile,
        debridFileContents: DebridFileContents
    ) = it.path.split("/").last() == debridFileContents.originalPath.split("/").last()

    private fun linkShouldBeReChecked(debridFile: DebridFile): Boolean {
        return when (debridFile) {
            is MissingFile -> debridavConfiguration.waitAfterMissing
            is ProviderError -> debridavConfiguration.waitAfterProviderError
            is NetworkError -> debridavConfiguration.waitAfterNetworkError
            is ClientError -> debridavConfiguration.waitAfterClientError
            is CachedFile -> null
        }?.let {
            return Instant.ofEpochMilli(debridFile.lastChecked).isBefore(
                Instant.now(clock).minus(it)
            )
        } ?: false
    }
}

fun List<DebridClient>.getClient(debridProvider: DebridProvider): DebridClient =
    this.first { it.getProvider() == debridProvider }

typealias GetCachedFilesResponses = List<GetCachedFilesResponse>
