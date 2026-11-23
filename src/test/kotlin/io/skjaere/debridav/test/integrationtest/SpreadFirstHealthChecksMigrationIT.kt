package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import io.skjaere.debridav.torrent.Status
import io.skjaere.debridav.torrent.Torrent
import io.skjaere.debridav.torrent.TorrentRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ResourceLoader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

private const val TORRENT_COUNT = 50
private const val PREFIX = "v32-"

/**
 * Exercises V32 against torrents without a last_verified, the state of every torrent added
 * before 1.0. Flyway has already run V32 on the (empty) test database at startup, so the rows
 * are seeded afterwards and the migration's SQL is run again directly. Everything happens in one
 * transaction that is rolled back: EmptyTorrentCleanupTask would otherwise delete the seeded,
 * file-less torrents under the test.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class SpreadFirstHealthChecksMigrationIT {

    @Autowired
    lateinit var torrentRepository: TorrentRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Test
    fun `V32 spreads the first checks of unverified torrents over the next day`() {
        //given
        val verified = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS)
        val migration = resourceLoader
            .getResource("classpath:db/migration/V32__spread_first_torrent_health_checks.sql")
            .getContentAsString(Charset.defaultCharset())
        val before = Instant.now()

        //when
        val lastVerifiedByName = TransactionTemplate(transactionManager).execute { tx ->
            tx.setRollbackOnly()
            torrentRepository.save(torrent("${PREFIX}already-verified").apply { lastVerified = verified })
            repeat(TORRENT_COUNT) { torrentRepository.save(torrent("${PREFIX}unverified-$it")) }
            entityManager.flush()
            jdbcTemplate.execute(migration)
            // Read back the way the health-check scheduler does, not from the persistence context.
            entityManager.clear()
            torrentRepository.findAll()
                .filter { it.name!!.startsWith(PREFIX) }
                .associate { it.name!! to it.lastVerified }
        }!!
        val after = Instant.now()

        //then: each unverified torrent becomes due within the next day, not all at once
        val spread = lastVerifiedByName.filterKeys { it != "${PREFIX}already-verified" }
        assertThat("unverified torrents read back", spread.size, `is`(TORRENT_COUNT))
        spread.forEach { (name, lastVerified) ->
            assertThat("last_verified of $name", lastVerified, notNullValue())
            assertThat(
                "last_verified of $name ($lastVerified) is within the past day (now: $after)",
                lastVerified!! > before.minus(Duration.ofDays(1)) && lastVerified < after,
                `is`(true)
            )
        }
        assertThat("distinct last_verified values", spread.values.toSet().size, greaterThan(TORRENT_COUNT / 2))
        assertThat(
            "the already-verified torrent is untouched",
            lastVerifiedByName["${PREFIX}already-verified"],
            `is`(verified)
        )
    }

    private fun torrent(name: String) = Torrent().apply {
        this.name = name
        hash = name
        savePath = "/downloads"
        status = Status.LIVE
        lastVerified = null
    }
}
