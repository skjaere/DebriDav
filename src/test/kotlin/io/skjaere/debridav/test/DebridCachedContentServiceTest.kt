package io.skjaere.debridav.test

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridCachedContentService
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.TorrentMagnet
import io.skjaere.debridav.debrid.client.easynews.EasynewsClient
import io.skjaere.debridav.debrid.client.premiumize.PremiumizeClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.DebridFileContents
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant

class DebridCachedContentServiceTest {
    private val easynewsClient = mockk<EasynewsClient>()
    private val premiumizeClient = mockk<PremiumizeClient>()
    private val debridavConfigurationProperties = mockk<DebridavConfigurationProperties>()
    private val clock = Clock.systemUTC()

    init {
        every { easynewsClient.getProvider() } returns DebridProvider.EASYNEWS
        every { premiumizeClient.getProvider() } returns DebridProvider.PREMIUMIZE
    }

    private val underTest = DebridCachedContentService(
        listOf(easynewsClient, premiumizeClient),
        debridavConfigurationProperties,
        clock,
    )

    @Test
    @Suppress("LongMethod")
    fun `that Easynews file gets mapped to correct torrent file`() {
        // given
        every { debridavConfigurationProperties.retriesOnProviderError } returns 5
        every { debridavConfigurationProperties.debridClients } returns listOf(
            DebridProvider.EASYNEWS,
            DebridProvider.PREMIUMIZE
        )

        coEvery { easynewsClient.isCached(any<TorrentMagnet>()) } returns true
        coEvery { premiumizeClient.isCached(any<TorrentMagnet>()) } returns true

        coEvery { easynewsClient.isLinkAlive(any()) } returns true
        coEvery { premiumizeClient.isLinkAlive(any()) } returns true


        val premiumizeCachedFiles = listOf(
            CachedFile(
                "releaseName/releaseName.mkv",
                100L,
                "video/mkv",
                "http://localhost/releaseName.mkv",
                emptyMap(),
                Instant.now().toEpochMilli(),
                DebridProvider.PREMIUMIZE
            ),
            CachedFile(
                "releaseName/releaseName.nfo",
                100L,
                "video/mkv",
                "http://localhost/releaseName.nfo",
                emptyMap(),
                Instant.now().toEpochMilli(),
                DebridProvider.PREMIUMIZE,
            )
        )

        val easyNewsCachedFiles = listOf(
            CachedFile(
                "releaseName.mkv",
                100,
                "video/mkv",
                "http://localhost/releaseName.mkv",
                emptyMap(),
                Instant.now().toEpochMilli(),
                DebridProvider.EASYNEWS,
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
            MatcherAssert.assertThat(
                cachedFiles,
                Matchers.allOf<List<DebridFileContents>>(
                    Matchers.hasSize(2),
                    Matchers.hasItem<DebridFileContents>(
                        Matchers.allOf<DebridFileContents>(
                            Matchers.hasProperty<DebridFileContents>(
                                "originalPath", Matchers.`is`("releaseName/releaseName.mkv")
                            ),
                            Matchers.hasProperty(
                                "debridLinks", Matchers.allOf<List<CachedFile>>(
                                    Matchers.hasSize(2),
                                    Matchers.hasItem<CachedFile>(
                                        Matchers.allOf<CachedFile>(
                                            Matchers.hasProperty("path", Matchers.`is`("releaseName.mkv")),
                                            Matchers.hasProperty("provider", Matchers.`is`(DebridProvider.EASYNEWS)),
                                        )

                                    ),
                                    Matchers.hasItem<CachedFile>(
                                        Matchers.allOf<CachedFile>(
                                            Matchers.hasProperty("path", Matchers.`is`("releaseName/releaseName.mkv")),
                                            Matchers.hasProperty("provider", Matchers.`is`(DebridProvider.PREMIUMIZE)),
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    Matchers.hasItem<DebridFileContents>(
                        Matchers.allOf(
                            Matchers.hasProperty<DebridFileContents>(
                                "originalPath", Matchers.`is`("releaseName/releaseName.nfo")
                            ),
                            Matchers.hasProperty<DebridFileContents>(
                                "debridLinks", Matchers.allOf<List<CachedFile>>(
                                    Matchers.hasSize(1),
                                    Matchers.hasItem<CachedFile>(
                                        Matchers.allOf<CachedFile>(
                                            Matchers.hasProperty("path", Matchers.`is`("releaseName/releaseName.nfo")),
                                            Matchers.hasProperty("provider", Matchers.`is`(DebridProvider.PREMIUMIZE)),
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
