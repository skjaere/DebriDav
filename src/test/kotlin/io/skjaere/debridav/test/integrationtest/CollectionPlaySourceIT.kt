package io.skjaere.debridav.test.integrationtest

import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.collection.play.DebridavUsenetSource
import io.skjaere.debridav.collection.play.PlaySession
import io.skjaere.debridav.collection.play.PlaySessionRegistry
import io.skjaere.debridav.collection.play.SessionTarget
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerNntpTest
import io.skjaere.debridav.test.integrationtest.config.TestSecurityConfiguration
import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import io.skjaere.mocknntp.testcontainer.client.NzbFileSpec
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.play.EpisodeNames
import io.skjaere.nzbstreamer.play.EpisodeNumbers
import io.skjaere.nzbstreamer.play.FetchException
import io.skjaere.nzbstreamer.play.FilePicker
import io.skjaere.nzbstreamer.play.NzbSource
import io.skjaere.nzbstreamer.play.PipelineEvent
import io.skjaere.nzbstreamer.play.PipelineStep
import io.skjaere.nzbstreamer.play.PlayCandidate
import io.skjaere.nzbstreamer.play.PlayPipeline
import io.skjaere.nzbstreamer.play.ProbeResult
import io.skjaere.nzbstreamer.play.StepStatus
import io.skjaere.nzbstreamer.play.TrackProbe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.endsWith
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.sameInstance
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody

/**
 * debridav's Usenet releases in the play loop: each fetched, checked and prepared in memory, the first that plays kept
 * in its session for ffprobe and FFmpeg to read; one that fails is passed over in the words the progress screen shows.
 */
@SpringBootTest(
    classes = [
        DebriDavApplication::class,
        IntegrationTestContextConfiguration::class,
        MiltonConfiguration::class,
        TestSecurityConfiguration::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=easynews"],
)
@MockServerNntpTest
class CollectionPlaySourceIT {

    @Autowired
    lateinit var mockNntpServerContainer: MockNntpServerContainer

    @Autowired
    lateinit var nzbStreamer: NzbStreamer

    @Autowired
    lateinit var registry: PlaySessionRegistry

    @Autowired
    lateinit var webTestClient: WebTestClient

    @AfterEach
    fun tearDown() {
        registry.closeAll()
        runBlocking {
            mockNntpServerContainer.client.clearYencBodyExpectations()
            mockNntpServerContainer.client.clearStatExpectations()
        }
    }

    @Test
    fun `releases are tried until one opens, and that one is kept in the session`() = runBlocking<Unit> {
        //given: a release whose NZB can't be fetched, one whose articles are gone, and a good one
        val gone = nzbOf(ByteArray(SIZE) { 1 }, articlesThere = false)
        mockNntpServerContainer.client.clearYencBodyExpectations()
        val data = ByteArray(SIZE) { (it % 253).toByte() }
        val good = nzbOf(data)
        val nzbs = mapOf("gone" to gone, "good" to good)
        val session = registry.open("tester")
        val source = source(session, NzbSource { candidate ->
            // One release's NZB is gone; its indexer still serves the others.
            nzbs[candidate.id] ?: throw FetchException("The indexer said no")
        })
        val keepAndPlay = TrackProbe { target -> target.keep(); ProbeResult.Playable() }

        //when
        val events = PlayPipeline({ source }, keepAndPlay, maxAttempts = 3)
            .play(listOf(candidate("refused"), candidate("gone"), candidate("good")), FilePicker.Largest).toList()

        //then: the first two were passed over, each at its step and in the progress screen's words
        val played = events.last()
        assertThat(played, instanceOf(PipelineEvent.Succeeded::class.java))
        played as PipelineEvent.Succeeded
        val failures = played.failedAttempts.map { (candidate, failure) -> candidate.id to failure.text }
        assertThat(failures.map { it.first }, equalTo(listOf("refused", "gone")))
        assertThat(failures[0].second, equalTo("Couldn't get the NZB: The indexer said no"))
        assertThat(failures[1].second, startsWith("Incomplete on your providers · "))
        assertThat(failures[1].second, endsWith("missing"))
        val failedSteps = events.filterIsInstance<PipelineEvent.Step>()
            .filter { it.status is StepStatus.Failed }
            .map { it.candidate.id to it.step }
        assertThat(
            failedSteps,
            equalTo(listOf("refused" to PipelineStep.FetchNzb, "gone" to PipelineStep.CheckAvailability)),
        )

        //then: the good one is the session's, and streams
        val target = played.target as SessionTarget
        assertThat(target.candidate.id, equalTo("good"))
        assertThat(session.release, sameInstance(target.release))
        val head = webTestClient.get().uri("/api/v1/collection/sessions/${session.token}/source")
            .header("Range", "bytes=0-1023").exchange()
            .expectStatus().isEqualTo(206).expectBody<ByteArray>().returnResult().responseBody!!
        assertThat(head.toList(), equalTo(data.copyOfRange(0, 1024).toList()))
    }

    @Test
    fun `a release whose probe finds nothing playable is let go of`() = runBlocking<Unit> {
        //given
        val first = nzbOf(ByteArray(SIZE) { 2 })
        val second = nzbOf(ByteArray(SIZE) { 3 })
        val nzbs = mapOf("first" to first, "second" to second)
        val session = registry.open("tester")
        val source = source(session, NzbSource { nzbs.getValue(it.id) })
        val kept = mutableListOf<Any?>()
        val probe = TrackProbe { target ->
            target.keep()
            kept += session.release
            if (target.candidate.id == "first") {
                ProbeResult.NoPlayableAudio(listOf("TrueHD 7.1"))
            } else {
                ProbeResult.Playable()
            }
        }

        //when
        val events = PlayPipeline({ source }, probe, maxAttempts = 3)
            .play(listOf(candidate("first"), candidate("second")), FilePicker.Largest).toList()

        //then
        val played = events.last() as PipelineEvent.Succeeded
        val (failed, failure) = played.failedAttempts.single()
        assertThat(failed.id, equalTo("first"))
        assertThat(failure.text, containsString("TrueHD 7.1"))
        assertThat(kept.size, equalTo(2))
        val target = played.target as SessionTarget
        assertThat("the first release is no longer held", session.release, sameInstance(target.release))
        assertThat(kept[0] === kept[1], equalTo(false))
    }

    @Test
    fun `a season pack is checked on the picked episode's articles only`() = runBlocking<Unit> {
        //given: two packs; the first misses an article of episode 1, the second of episode 2
        val otherEpisodeGone = packOf(missing = "E01")
        val wantedEpisodeGone = packOf(missing = "E02")
        val nzbs = mapOf("other-gone" to otherEpisodeGone, "wanted-gone" to wantedEpisodeGone)
        val session = registry.open("tester")
        val source = source(session, NzbSource { nzbs.getValue(it.id) })
        val episode2 = FilePicker.Episode(1, 2, EPISODE_NAMES)
        val pack = { id: String -> PlayCandidate(id = id, title = "Show.S01.1080p.WEB-DL-$id", indexer = "geek") }

        //when
        val events = PlayPipeline({ source }, { it.keep(); ProbeResult.Playable() }, maxAttempts = 2)
            .play(listOf(pack("wanted-gone"), pack("other-gone")), episode2).toList()

        //then: the pack missing the wanted episode's article failed its check; the other played episode 2
        val played = events.last() as PipelineEvent.Succeeded
        val failed = played.failedAttempts.single()
        assertThat(failed.first.id, equalTo("wanted-gone"))
        assertThat(failed.second.text, startsWith("Incomplete on your providers"))
        assertThat(played.target.candidate.id, equalTo("other-gone"))
        assertThat(played.target.file.name, equalTo("Show.S01E02.1080p.mkv"))
    }

    @Test
    fun `a closed session holds no release`() = runBlocking<Unit> {
        val good = nzbOf(ByteArray(SIZE) { 4 })
        val session = registry.open("tester")
        val source = source(session, NzbSource { good })
        PlayPipeline({ source }, { it.keep(); ProbeResult.Playable() }, maxAttempts = 1)
            .play(listOf(candidate("good")), FilePicker.Largest).toList()

        registry.close(session.token)

        assertThat(session.release, nullValue())
    }

    private fun source(session: PlaySession, nzbs: NzbSource) =
        DebridavUsenetSource(nzbs, nzbStreamer, session, "http://localhost/unused/${session.token}")

    private fun candidate(id: String) =
        PlayCandidate(id = id, title = "Some.Movie.2024.1080p.WEB-DL-$id", indexer = "geek")

    /** An NZB of [data] on the mock server, whose articles answer STAT as there or not, as [articlesThere] says. */
    private suspend fun nzbOf(data: ByteArray, articlesThere: Boolean = true): ByteArray {
        val nzb = mockNntpServerContainer.client
            .prepareArchiveNzb(mapOf("movie.mkv" to data), ContainerType.RAR5)
            .toByteArray()
        NzbParser.parse(nzb).files.flatMap { it.segments }.forEach {
            mockNntpServerContainer.client.addStatExpectation("<${it.articleId}>", articlesThere)
        }
        return nzb
    }

    /** A season pack of two loose episodes; the one [missing] names answers STAT as gone. */
    private suspend fun packOf(missing: String): ByteArray {
        val files = listOf("E01", "E02").map { episode ->
            NzbFileSpec("Show.S01$episode.1080p.mkv", ByteArray(SIZE) { (it + episode.hashCode()).toByte() })
        }
        val nzb = mockNntpServerContainer.client.prepareNzb(files).toByteArray()
        NzbParser.parse(nzb).files.forEach { file ->
            val there = missing !in file.subject
            file.segments.forEach { mockNntpServerContainer.client.addStatExpectation("<${it.articleId}>", there) }
        }
        return nzb
    }

    private companion object {
        const val SIZE = 48 * 1024

        /** Stands in for the release parser: what SxxEyy a file name holds. */
        val EPISODE_NAMES = EpisodeNames { name ->
            val match = Regex("""S(\d+)E(\d+)""", RegexOption.IGNORE_CASE).find(name)
            EpisodeNumbers(
                listOfNotNull(match?.groupValues?.get(1)?.toInt()),
                listOfNotNull(match?.groupValues?.get(2)?.toInt()),
            )
        }
    }
}
