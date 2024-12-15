package io.skjaere.debridav.debrid

import io.skjaere.debridav.debrid.client.DebridUsenetClient
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.DownloadLinkServiceError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.DownloadLinkUnknownError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadClientError
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadLinkDownloadNotFound
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.SuccessfulRequestDownloadLinkResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.fs.DebridUsenetFileContents
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class DebridLinkUsenetService {

    suspend fun getFreshDebridLinkFromUsenet(
        debridFileContents: DebridUsenetFileContents,
        debridClient: DebridUsenetClient
    ): DebridFile = coroutineScope {
        debridFileContents.debridLinks
            .firstOrNull { it.provider == debridClient.getProvider() }
            ?.let {
                when (it) {
                    is CachedFile -> {
                        getFreshDebridLinkFromCachedFile(it, debridClient, debridFileContents)
                    }

                    else -> error("Only CachedFile may have it's link refreshed")
                }
            } ?: kotlin.run {
            MissingFile(debridClient.getProvider(), Instant.now().toEpochMilli())
        }
    }

    private suspend fun getFreshDebridLinkFromCachedFile(
        debridLink: CachedFile,
        debridClient: DebridUsenetClient,
        debridFileContents: DebridUsenetFileContents
    ): DebridFile {
        return debridLink.params["downloadId"]?.let { downloadFileId ->
            debridClient.getStreamableLink(debridFileContents.usenetDownloadId, downloadFileId)
                .let { freshLink ->
                    when (freshLink) {
                        DownloadLinkServiceError -> ProviderError(
                            debridClient.getProvider(),
                            Instant.now().toEpochMilli()
                        )

                        DownloadLinkUnknownError -> NetworkError(
                            debridClient.getProvider(),
                            Instant.now().toEpochMilli()
                        )

                        RequestDownloadClientError -> ClientError(
                            debridClient.getProvider(),
                            Instant.now().toEpochMilli()
                        )

                        RequestDownloadLinkDownloadNotFound -> MissingFile( //
                            debridClient.getProvider(),
                            Instant.now().toEpochMilli()
                        )

                        is SuccessfulRequestDownloadLinkResponse -> debridLink.withNewLink(freshLink.link)
                    }

                }
        } ?: error("downloadId not present")
    }
}
