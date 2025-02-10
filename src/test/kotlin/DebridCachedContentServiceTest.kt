import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.CachedContentKey
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.client.easynews.EasynewsClient
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeClient
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.DebridProvider
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasProperty
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class DebridCachedContentServiceTest {
    private val easynewsClient = mockk<EasynewsClient>()
    private val premiumizeClient = mockk<PremiumizeClient>()
    private val debridavConfiguration = mockk<DebridavConfiguration>()
    private val clock = Clock.systemUTC()

    private val underTest = DebridCachedContentService(
        listOf(easynewsClient, premiumizeClient),
        debridavConfiguration,
        clock,
    )

    @Test
    @Suppress("LongMethod")
    fun `that Easynews file gets mapped to correct torrent file`() {
        // given
        every { debridavConfiguration.retriesOnProviderError } returns 5
        every { debridavConfiguration.debridClients } returns listOf(DebridProvider.EASYNEWS, DebridProvider.PREMIUMIZE)
        every { easynewsClient.getProvider() } returns DebridProvider.EASYNEWS
        every { premiumizeClient.getProvider() } returns DebridProvider.PREMIUMIZE

        coEvery { easynewsClient.isCached(any<TorrentMagnet>()) } returns true
        coEvery { premiumizeClient.isCached(any<CachedContentKey>()) } returns true

        coEvery { easynewsClient.isLinkAlive(any()) } returns true
        coEvery { premiumizeClient.isLinkAlive(any()) } returns true


        val premiumizeCachedFiles = listOf(
            CachedFile(
                "releaseName/releaseName.mkv",
                100,
                "video/mkv",
                "http://localhost/releaseName.mkv",
                DebridProvider.PREMIUMIZE,
                Instant.now().toEpochMilli(),
            ),
            CachedFile(
                "releaseName/releaseName.nfo",
                100,
                "video/mkv",
                "http://localhost/releaseName.nfo",
                DebridProvider.PREMIUMIZE,
                Instant.now().toEpochMilli(),
            )
        )

        val easyNewsCachedFiles = listOf(
            CachedFile(
                "releaseName.mkv",
                100,
                "video/mkv",
                "http://localhost/releaseName.mkv",
                DebridProvider.EASYNEWS,
                Instant.now().toEpochMilli(),
            )
        )
        coEvery {
            easynewsClient.getCachedFiles(
                any<TorrentMagnet>()
            )
        } returns easyNewsCachedFiles
        coEvery {
            premiumizeClient.getCachedFiles(
                any<TorrentMagnet>()
            )
        } returns premiumizeCachedFiles

        // when
        runTest {
            val cachedFiles = underTest
                .addContent(TorrentMagnet("test"))

            // then
            assertThat(
                cachedFiles,
                allOf<List<DebridFileContents>>(
                    hasSize(2),
                    hasItem<DebridFileContents>(
                        allOf<DebridFileContents>(
                            hasProperty<DebridFileContents>(
                                "originalPath", `is`("releaseName/releaseName.mkv")
                            ),
                            hasProperty(
                                "debridLinks", allOf<List<CachedFile>>(
                                    hasSize(2),
                                    hasItem<CachedFile>(
                                        allOf<CachedFile>(
                                            hasProperty("path", `is`("releaseName.mkv")),
                                            hasProperty("provider", `is`(DebridProvider.EASYNEWS)),
                                        )

                                    ),
                                    hasItem<CachedFile>(
                                        allOf<CachedFile>(
                                            hasProperty("path", `is`("releaseName/releaseName.mkv")),
                                            hasProperty("provider", `is`(DebridProvider.PREMIUMIZE)),
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }
    }
}
