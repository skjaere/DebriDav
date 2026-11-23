package io.skjaere.debridav.test

import com.vdsirotkin.pgmq.PgmqClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridProvider
import io.skjaere.debridav.debrid.client.DebridCachedTorrentClient
import io.skjaere.debridav.debrid.model.DebridFileUnavailableError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.health.HealthMetrics
import io.skjaere.debridav.health.PgmqHealthQueueRepository
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import io.skjaere.debridav.torrent.pgmq.TorrentHealthCheckHandler
import io.skjaere.debridav.torrent.pgmq.TorrentHealthCheckMessage
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

private const val TORRENT_ID = 1L
private const val REPAIR_QUEUE = "torrent_health_repair"

class TorrentHealthCheckHandlerTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private val torrent = Torrent().apply {
        id = TORRENT_ID
        name = "torrent"
        magnet = MAGNET
    }
    private val torrentRepository = mockk<TorrentRepository> {
        every { findById(TORRENT_ID) } returns Optional.of(torrent)
        every { save(any()) } answers { firstArg() }
    }
    private val pgmqClient = mockk<PgmqClient>(relaxed = true)
    private val pgmqHealthQueueRepository = mockk<PgmqHealthQueueRepository> {
        every { isRepairQueued(any()) } returns false
    }
    private val configuration = DebridavConfigurationProperties()

    private fun handler(vararg clients: DebridCachedTorrentClient) = TorrentHealthCheckHandler(
        torrentRepository,
        pgmqClient,
        Clock.fixed(now, ZoneOffset.UTC),
        HealthMetrics(SimpleMeterRegistry()),
        clients.toList(),
        configuration,
        pgmqHealthQueueRepository,
    )

    private fun client(provider: DebridProvider, answer: suspend () -> Boolean) =
        mockk<DebridCachedTorrentClient> {
            every { getProvider() } returns provider
            coEvery { isHealthy(any()) } coAnswers { answer() }
        }

    private fun providerError() = DebridProviderError("503 Service Unavailable", 503, "/cache/check")

    @Test
    fun `does not repair when a provider that could not be asked might still have the torrent`() {
        //given
        configuration.debridClients = listOf(DebridProvider.PREMIUMIZE, DebridProvider.TORBOX)
        val premiumize = client(DebridProvider.PREMIUMIZE) { false }
        val torBox = client(DebridProvider.TORBOX) { throw providerError() }

        //when
        handler(premiumize, torBox).handle(TorrentHealthCheckMessage(TORRENT_ID))

        //then
        verify(exactly = 0) { pgmqClient.send(REPAIR_QUEUE, any(), any()) }
    }

    @Test
    fun `repairs when every provider says it does not have the torrent`() {
        //given
        configuration.debridClients = listOf(DebridProvider.PREMIUMIZE, DebridProvider.TORBOX)
        val premiumize = client(DebridProvider.PREMIUMIZE) { false }
        val torBox = client(DebridProvider.TORBOX) { false }

        //when
        handler(premiumize, torBox).handle(TorrentHealthCheckMessage(TORRENT_ID))

        //then
        verify(exactly = 1) { pgmqClient.send(REPAIR_QUEUE, any(), any()) }
        assertThat(torrent.lastVerified, `is`(now))
    }

    @Test
    fun `repairs when the only provider reports the torrent permanently unavailable`() {
        //given
        configuration.debridClients = listOf(DebridProvider.REAL_DEBRID)
        val realDebrid = client(DebridProvider.REAL_DEBRID) {
            throw DebridFileUnavailableError("infringing_file", 451, "/torrents/info", "infringing_file")
        }

        //when
        handler(realDebrid).handle(TorrentHealthCheckMessage(TORRENT_ID))

        //then
        verify(exactly = 1) { pgmqClient.send(REPAIR_QUEUE, any(), any()) }
    }

    @Test
    fun `does not repair when no configured provider can check torrents`() {
        //given: Easynews only serves usenet, so it has no torrent client
        configuration.debridClients = listOf(DebridProvider.EASYNEWS)
        val premiumize = client(DebridProvider.PREMIUMIZE) { false }

        //when
        handler(premiumize).handle(TorrentHealthCheckMessage(TORRENT_ID))

        //then
        verify(exactly = 0) { pgmqClient.send(REPAIR_QUEUE, any(), any()) }
        coVerify(exactly = 0) { premiumize.isHealthy(any()) }
    }

    @Test
    fun `does not count the check as done when the repair cannot be enqueued`() {
        //given
        configuration.debridClients = listOf(DebridProvider.PREMIUMIZE)
        val premiumize = client(DebridProvider.PREMIUMIZE) { false }
        every { pgmqClient.send(REPAIR_QUEUE, any(), any()) } throws IllegalStateException("connection lost")

        //when
        handler(premiumize).handle(TorrentHealthCheckMessage(TORRENT_ID))

        //then: still due, so the next sweep checks it again and retries the repair
        assertThat(torrent.lastVerified, nullValue())
    }
}
