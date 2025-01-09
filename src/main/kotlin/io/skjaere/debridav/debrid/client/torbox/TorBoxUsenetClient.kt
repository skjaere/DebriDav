package io.skjaere.debridav.debrid.client.torbox

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.debrid.DebridUsenetDownloadService
import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.CheckCachedResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.CreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.DownloadSlotsFullUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.ErrorCreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.FailedCreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetResponseListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.usenet.RequestDLResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.SuccessfulCreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.AddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.FailedAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.ServiceErrorAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.SuccessfulAddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.ListClientError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.ListServiceError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.SuccessfulDownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.DownloadLinkServiceError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.DownloadLinkUnknownError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadClientError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadLinkDownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadLinkResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.SuccessfulRequestDownloadLinkResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.exceptions.DownloadLinkClientException
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.exceptions.DownloadLinkServiceException
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.UnknownError
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.fs.DebridUsenetFileContents
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.serialization.SerialName
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import java.io.InputStream
import java.time.Instant

private const val REQUEST_TIMEOUT_MS = 40_000L
private const val REQUEST_RETRIES = 3L
private const val REQUEST_RETRIES_DELAY_MS = 1_000L
private const val DEFAULT_429_WAIT_MS = 2_000L

@Component
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
class TorBoxUsenetClient(
    override val httpClient: HttpClient, private val torBoxConfiguration: TorBoxConfiguration
) : DebridUsenetClient {
    companion object {
        const val DATABASE_ERROR = "DATABASE_ERROR"
    }

    private val logger = LoggerFactory.getLogger(DebridUsenetDownloadService::class.java)

    override suspend fun addNzb(inputStream: InputStream, fileName: String): AddNzbResponse {
        return flow {
            val resp = httpClient.post("${torBoxConfiguration.apiUrl}/api/usenet/createusenetdownload") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("name", fileName.substringBeforeLast("."))
                            append("file", inputStream.readAllBytes(), Headers.build {
                                append(
                                    HttpHeaders.ContentDisposition,
                                    "filename=$fileName"
                                )
                                append(HttpHeaders.ContentType, "application/x-nzb")
                            })
                        }, boundary = "WebAppBoundary"
                    )
                )
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(torBoxConfiguration.apiKey)
                }
                timeout {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }

            val parsedBody = try {
                resp.body<CreateUsenetDownloadResponse>()
            } catch (e: JsonConvertException) {
                logger.error(resp.body<String>(), e)
                logger.error("could not deserialize add nzb response", e)
                ErrorCreateUsenetDownloadResponse(
                    "could not deserialize add nzb response: ${resp.body<String>()}"
                )
            }
            emit(mapResponseToReturnValue(parsedBody, fileName))
        }.retry(REQUEST_RETRIES)
            .catch { cause ->
                logger.warn("error during adding nzb response", cause)
                emit(ServiceErrorAddNzbResponse("IOException encountered when attempting to add nzb: ${cause.cause}"))
            }
            .first()
    }

    private suspend fun mapResponseToReturnValue(
        parsedBody: CreateUsenetDownloadResponse,
        fileName: String
    ): AddNzbResponse {
        return when (parsedBody) {
            is DownloadSlotsFullUsenetDownloadResponse -> FailedAddNzbResponse("download slots full")
            is FailedCreateUsenetDownloadResponse -> {
                deleteDownload(parsedBody.data.usenetDownloadId)
                FailedAddNzbResponse(parsedBody.error!!)
            }

            is SuccessfulCreateUsenetDownloadResponse -> SuccessfulAddNzbResponse(
                parsedBody.data!!.usenetDownloadId.toLong(),
                fileName.substringBeforeLast("."),
                parsedBody.data.hash
            )

            is ErrorCreateUsenetDownloadResponse -> TODO()
        }
    }

    override suspend fun getDownloads(ids: List<Long>): Map<Long, DownloadInfo> = coroutineScope {
        ids.associateWith {
            async {
                getDownloadInfo(it)
            }
        }
            .map { it.key to it.value.await() }
            .toMap()

    }

    override fun getProvider(): DebridProvider = DebridProvider.TORBOX
    override fun getMsToWaitFrom429Response(httpResponse: HttpResponse): Long {
        return (httpResponse.headers["x-ratelimit-after"]?.toLong() ?: DEFAULT_429_WAIT_MS).let {
            if (it == 0L) 1 else it
        }
    }

    override suspend fun getCachedFilesFromUsenetInfoListItem(
        listItemFile: GetUsenetResponseListItemFile,
        downloadId: Long
    ): DebridFile = coroutineScope {
        flow { emit(getStreamableLink(downloadId, listItemFile.id)) }
            .retry(REQUEST_RETRIES) { e ->
                (e is DownloadLinkServiceException).also { if (it) delay(REQUEST_RETRIES_DELAY_MS) }
            }.catch { e ->
                when (e) {
                    is DownloadLinkServiceException -> emit(DownloadLinkServiceError)
                    is DownloadLinkClientException -> emit(RequestDownloadClientError)
                    else -> emit(DownloadLinkUnknownError)
                }
            }.first().let {
                when (it) {
                    is SuccessfulRequestDownloadLinkResponse -> {
                        getCachedFileFromDownloadLinkResponse(listItemFile, downloadId, it)
                    }

                    is RequestDownloadLinkDownloadNotFound -> MissingFile(
                        DebridProvider.TORBOX,
                        Instant.now().toEpochMilli()
                    )

                    is DownloadLinkServiceError -> ProviderError(
                        DebridProvider.TORBOX,
                        Instant.now().toEpochMilli()
                    )

                    is DownloadLinkUnknownError -> UnknownError(
                        DebridProvider.TORBOX,
                        Instant.now().toEpochMilli()
                    )

                    is RequestDownloadClientError -> ClientError(
                        DebridProvider.TORBOX,
                        Instant.now().toEpochMilli()
                    )
                }
            }
    }

    override suspend fun deleteDownload(id: String): Boolean {
        return flow {
            emit(
                httpClient.post(
                    "${torBoxConfiguration.apiUrl}/api/usenet/controlusenetdownload"
                ) {
                    headers {
                        contentType(ContentType.Application.Json)
                        accept(ContentType.Application.Json)
                        bearerAuth(torBoxConfiguration.apiKey)
                    }
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
                    setBody {
                        ControlUsenetRequest(
                            id,
                            ControlUsenetRequest.Operation.DELETE,
                            false
                        )
                    }
                }.status.isSuccess()
            )
        }.retry(REQUEST_RETRIES).first()
    }

    data class ControlUsenetRequest(
        @SerialName("usenet_id") val usenetId: String,
        @SerialName("operation") val operation: Operation,
        val all: Boolean
    ) {
        enum class Operation {
            DELETE, PAUSE, RESUME
        }
    }

    private fun getCachedFileFromDownloadLinkResponse(
        listItemFile: GetUsenetResponseListItemFile,
        downloadId: Long,
        it: SuccessfulRequestDownloadLinkResponse
    ) = CachedFile(
        path = listItemFile.name,
        size = listItemFile.size,
        mimeType = listItemFile.mimetype,
        params = mapOf("fileId" to listItemFile.id, "downloadId" to downloadId.toString()),
        link = it.link,
        provider = DebridProvider.TORBOX,
        lastChecked = Instant.now().toEpochMilli()
    )

    override suspend fun getStreamableLink(downloadId: Long, downloadFileId: String): RequestDownloadLinkResponse {
        val resp = try {
            httpClient.get(
                "${torBoxConfiguration.apiUrl}/api/usenet/requestdl"
                        + "?token=${torBoxConfiguration.apiKey}"
                        + "&usenet_id=$downloadId"
                        + "&fileId=$downloadFileId"
            ) {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(torBoxConfiguration.apiKey)
                }
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            }
        } catch (e: IOException) {
            throw DownloadLinkServiceException("Network error getting link from TorBox", e)
        }
        val parsedResponse = try {
            resp.body<RequestDLResponse>()
        } catch (e: JsonConvertException) {
            logger.error("error deserializing requestdl response from TorBox: ${resp.body<String>()}", e)
            throw DownloadLinkClientException("error deserializing requestdl response", e)
        }
        return if (!parsedResponse.success || parsedResponse.data == null) {
            logger.error("error getting download link for id:$downloadId response: $parsedResponse")
            if (parsedResponse.error == DATABASE_ERROR) {
                return RequestDownloadLinkDownloadNotFound
            } else {
                throw DownloadLinkServiceException("Unknown error: ${parsedResponse.error} getting link from TorBox.")
            }
        } else SuccessfulRequestDownloadLinkResponse(parsedResponse.data)
    }

    override suspend fun isCached(debridUsenetFileContents: DebridUsenetFileContents): Boolean {
        return try {
            getIsCachedResponse(debridUsenetFileContents)
                .body<CheckCachedResponse>()
                .data?.containsKey(debridUsenetFileContents.hash) ?: false
        } catch (e: JsonConvertException) {
            logger.error("error deserializing check cached response", e)
            false
        } catch (e: IOException) {
            logger.error("error encountered while checking if hash is cached", e)
            false
        }
    }

    private suspend fun getIsCachedResponse(debridUsenetFileContents: DebridUsenetFileContents) =
        httpClient.get(
            "${torBoxConfiguration.apiUrl}/api/usenet/checkcached"
                    + "?hash=${debridUsenetFileContents.hash}"
                    + "&format=object"
        ) {
            headers {
                accept(ContentType.Application.Json)
                bearerAuth(torBoxConfiguration.apiKey)
            }
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }

    override suspend fun getDownloadInfo(id: Long): DownloadInfo {
        logger.debug("Getting usenet download for $id")
        val resp = try {
            httpClient.get("${torBoxConfiguration.apiUrl}/api/usenet/mylist?id=$id") {
                headers {
                    accept(ContentType.Application.Json)
                    bearerAuth(torBoxConfiguration.apiKey)
                }
            }
        } catch (e: IOException) {
            logger.error("Error getting usenet metadata from TorBox", e)
            return ListServiceError
        }
        return try {
            val parsedResponse = resp.body<GetUsenetListResponse>()

            if (parsedResponse.error == DATABASE_ERROR || parsedResponse.data == null) {
                DownloadNotFound
            } else {
                SuccessfulDownloadInfo(parsedResponse.data)
            }
        } catch (e: JsonConvertException) {
            logger.error("error deserializing ${resp.body<String>()}", e)
            ListClientError
        }
    }
}
