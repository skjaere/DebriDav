package io.skjaere.debridav.collection.play

import io.skjaere.debridav.collection.indexer.IndexerService
import io.skjaere.debridav.collection.library.ArrLibraryClient
import io.skjaere.debridav.collection.library.LanguageCodes
import io.skjaere.newznab.Indexer
import io.skjaere.newznab.Newznab
import io.skjaere.newznab.NewznabError
import io.skjaere.newznab.NewznabException
import io.skjaere.newznab.TvIds
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.play.EpisodeNames
import io.skjaere.nzbstreamer.play.EpisodeNumbers
import io.skjaere.nzbstreamer.play.FetchException
import io.skjaere.nzbstreamer.play.FetchedNzb
import io.skjaere.nzbstreamer.play.FilePicker
import io.skjaere.nzbstreamer.play.NzbSource
import io.skjaere.nzbstreamer.play.PipelineEvent
import io.skjaere.nzbstreamer.play.PlayCandidate
import io.skjaere.nzbstreamer.play.PlayPipeline
import io.skjaere.nzbstreamer.play.ProbedTrack
import io.skjaere.nzbstreamer.play.StepStatus
import io.skjaere.releases.RankedRelease
import io.skjaere.releases.RankingContext
import io.skjaere.releases.ReleaseParser
import io.skjaere.releases.Severity
import io.skjaere.releases.search.EpisodeReleaseFinder
import io.skjaere.releases.search.EpisodeRequest
import io.skjaere.releases.search.FinderEvent
import io.skjaere.releases.search.IndexerReport
import io.skjaere.releases.search.IndexerSearch
import io.skjaere.releases.search.MovieReleaseFinder
import io.skjaere.releases.search.MovieRequest
import io.skjaere.releases.search.Release
import io.skjaere.releases.search.ShowRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * What to play, and what the browser that plays it can decode. Either [movieId], or [seriesId] with season and
 * episode.
 */
data class PlayRequest(
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val capabilities: BrowserCapabilities = BrowserCapabilities(),
)

/** A play started: its session's token, which addresses everything of it, and where its progress streams. */
data class PlayStarted(val token: String, val events: String)

/** A play that can't start, in words the page shows. */
class PlayRejected(message: String) : RuntimeException(message)

/**
 * Plays a movie or an episode from the collection: searches the indexers for its releases (release-finder), ranks them
 * for the browser, then tries them in turn (nzb-streamer's play loop) until one opens and has video and audio the
 * browser decodes. Everything runs in the play's session, whose events tell the progress screen each step.
 */
@Service
class PlayService(
    private val registry: PlaySessionRegistry,
    private val library: ArrLibraryClient,
    private val indexerService: IndexerService,
    private val newznab: Newznab,
    private val nzbStreamer: NzbStreamer,
    private val properties: CollectionPlayProperties,
    private val serverPort: ServerPort,
) {
    private val log = LoggerFactory.getLogger(PlayService::class.java)
    private val search by lazy { IndexerSearch(newznab) }

    fun start(request: PlayRequest, user: String): PlayStarted {
        val indexers = indexerService.indexers().filter { it.enabled }
        if (indexers.isEmpty()) throw PlayRejected("Add a Newznab indexer to play from the collection")
        val wanted = runBlocking { lookUp(request) }
        val session = registry.open(user)
        session.scope.launch { play(session, wanted, indexers, request.capabilities) }
        return PlayStarted(session.token, "/api/v1/collection/sessions/${session.token}/events")
    }

    /** What [request] asks to play, as Radarr or Sonarr has it. */
    private suspend fun lookUp(request: PlayRequest): Wanted {
        val (seriesId, season, number) = Triple(request.seriesId, request.season, request.episode)
        return when {
            request.movieId != null -> lookUpMovie(request.movieId)
            seriesId != null && season != null && number != null -> lookUpEpisode(seriesId, season, number)
            else -> throw PlayRejected("Say which movie, or which series, season and episode")
        }
    }

    private suspend fun lookUpMovie(id: Long): Wanted.Movie {
        val movie = library.movie(id) ?: throw PlayRejected("Radarr has no movie $id")
        return Wanted.Movie(
            MovieRequest(
                title = movie.title,
                year = movie.year,
                imdb = movie.imdbId?.takeIf { it.isNotBlank() },
                tmdb = movie.tmdbId?.takeIf { it > 0 },
                originalLanguage = LanguageCodes.of(movie.originalLanguage?.name),
                runtimeMinutes = movie.runtime?.takeIf { it > 0 },
            ),
        )
    }

    private suspend fun lookUpEpisode(seriesId: Long, season: Int, number: Int): Wanted.Episode {
        val series = library.series(seriesId) ?: throw PlayRejected("Sonarr has no series $seriesId")
        val episodes = library.episodes(seriesId)
        val episode = episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == number }
            ?: throw PlayRejected("Sonarr has no episode S${season}E$number of ${series.title}")
        val show = ShowRef(
            name = series.title,
            year = series.year,
            ids = TvIds(tvdb = series.tvdbId, tmdb = series.tmdbId, imdb = series.imdbId, tvmaze = series.tvMazeId),
            originalLanguage = LanguageCodes.of(series.originalLanguage?.name),
        )
        return Wanted.Episode(
            EpisodeRequest(
                show = show,
                season = season,
                episode = number,
                airDate = episode.airDate?.let { date ->
                    try {
                        LocalDate.parse(date)
                    } catch (_: DateTimeParseException) {
                        null
                    }
                },
                runtimeMinutes = (episode.runtime ?: series.runtime)?.takeIf { it > 0 },
                seasonEpisodes = episodes.count { it.seasonNumber == season },
            ),
        )
    }

    private suspend fun play(
        session: PlaySession,
        wanted: Wanted,
        indexers: List<Indexer>,
        capabilities: BrowserCapabilities,
    ) {
        val events = session.events
        try {
            val context = RankingContext(capabilities.toDevice())
            val found = when (wanted) {
                is Wanted.Movie -> findMovie(session, wanted.request, indexers, context)
                is Wanted.Episode -> findEpisode(session, wanted.request, indexers, context)
            }
            if (found.isEmpty()) {
                events.emit(ExhaustedEvent(emptyList(), notTried = 0, skipped = 0))
                return
            }
            val byId = found.associateBy { it.key }
            val byIndexer = indexers.associateBy { it.id }
            val nzbs = object : NzbSource {
                override suspend fun fetch(candidate: PlayCandidate): ByteArray = fetchWithPassword(candidate).bytes

                override suspend fun fetchWithPassword(candidate: PlayCandidate): FetchedNzb =
                    download(byId.getValue(candidate.id), byIndexer)
            }
            val source = DebridavUsenetSource(nzbs, nzbStreamer, session, serverPort.sourceUri(session))
            val probe = FfprobeTrackProbe(properties.ffprobe, capabilities, session)
            val pickFile = when (wanted) {
                is Wanted.Movie -> FilePicker.Largest
                is Wanted.Episode -> FilePicker.Episode(wanted.request.season, wanted.request.episode, EPISODE_NAMES)
            }
            val candidates = found.map { candidate(it) }
            PlayPipeline({ source }, probe, maxAttempts = properties.maxAttempts)
                .play(candidates, pickFile)
                .collect { event -> toPlayEvent(event, capabilities)?.let(events::emit) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log.warn("play session {} failed: {}", session.id, e.message, e)
            events.emit(ErrorEvent(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private suspend fun findMovie(
        session: PlaySession,
        request: MovieRequest,
        indexers: List<Indexer>,
        context: RankingContext,
    ): List<Release> {
        session.events.emit(SearchStageEvent("Movie", indexers.size))
        val result = MovieReleaseFinder(search).find(request, indexers, context.copy(
            runtimeMinutes = request.runtimeMinutes,
            originalLanguage = request.originalLanguage,
        )).lastOrNull() ?: return emptyList()
        result.indexers.forEach { session.events.emit(indexerEvent("Movie", it)) }
        session.events.emit(searchDone(result.playable.size, result.hidden.map { it.ranked }))
        return result.playable.map { it.release }
    }

    private suspend fun findEpisode(
        session: PlaySession,
        request: EpisodeRequest,
        indexers: List<Indexer>,
        context: RankingContext,
    ): List<Release> {
        var found: List<Release> = emptyList()
        EpisodeReleaseFinder(search).find(request, indexers, context).collect { event ->
            when (event) {
                is FinderEvent.StageStarted -> session.events.emit(SearchStageEvent(event.stage.label, event.indexers))
                is FinderEvent.IndexerAnswered -> session.events.emit(indexerEvent(event.stage.label, event.report))
                is FinderEvent.Finished -> {
                    session.events.emit(searchDone(event.result.playable.size, event.result.hidden.map { it.ranked }))
                    found = event.result.playable.map { it.release }
                }
                // Torrents checked after the search ended; this search has none.
                is FinderEvent.Joined -> Unit
            }
        }
        return found
    }

    /** [release]'s NZB from its indexer; an indexer that refuses grabs (its limit, its login) isn't asked again. */
    private suspend fun download(release: Release, indexers: Map<String, Indexer>): FetchedNzb {
        val indexer = indexers[release.item.indexerId] ?: throw FetchException("Its indexer was removed")
        return try {
            newznab.download(indexer, release.item).let { FetchedNzb(it.bytes, it.password) }
        } catch (e: NewznabException) {
            val refusesGrabs = e.error is NewznabError.ApiLimitReached ||
                e.error is NewznabError.DownloadLimitReached ||
                e.error is NewznabError.Auth ||
                e.error is NewznabError.Disabled
            throw FetchException("${indexer.name}: ${e.error.message}", blocksIndexer = refusesGrabs, cause = e)
        }
    }

    private fun candidate(release: Release) = PlayCandidate(
        id = release.key,
        title = release.item.title,
        indexer = release.item.indexerId,
        passworded = release.item.passworded,
    )

    private fun indexerEvent(stage: String, report: IndexerReport) = SearchIndexerEvent(
        stage = stage,
        indexer = report.indexer.name,
        by = report.by,
        found = report.found,
        accepted = report.accepted,
        error = report.error?.message,
        skipped = report.skipped,
    )

    /** How many releases can play; why the others can't, the commonest reasons first. */
    private fun searchDone(playable: Int, hidden: List<RankedRelease>): SearchDoneEvent {
        val reasons = hidden
            .flatMap { ranked -> ranked.reasons.filter { it.severity == Severity.Blocking }.map { it.text }.distinct() }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(REASONS_SHOWN)
            .map { (reason, count) -> if (count == 1) reason else "$reason ($count)" }
        return SearchDoneEvent(playable, hidden.size, reasons)
    }

    private fun toPlayEvent(event: PipelineEvent, capabilities: BrowserCapabilities): PlayEvent? = when (event) {
        is PipelineEvent.AttemptStarted -> AttemptEvent(event.attempt, event.of, info(event.candidate))
        is PipelineEvent.Step -> stepEvent(event)
        is PipelineEvent.Skipped -> SkippedEvent(info(event.candidate), event.reason)
        is PipelineEvent.Retrying -> StepEvent(event.candidate.id, "Retrying", "failed", failure = event.failure.text)
        is PipelineEvent.Succeeded -> PlayingEvent(
            release = info(event.target.candidate),
            video = event.tracks?.video?.firstOrNull()?.let { track ->
                VideoInfo(track.label, track.width, track.height, event.video?.fps, event.video?.durationMs)
            },
            audio = event.tracks?.audio.orEmpty().map { trackInfo(it, capabilities.decodesAudio(codecOf(it))) },
            subtitles = event.tracks?.text.orEmpty().map { trackInfo(it, playable = false) },
            failedAttempts = event.failedAttempts.map { (candidate, failure) -> failureInfo(candidate, failure.text) },
        )
        is PipelineEvent.Exhausted -> ExhaustedEvent(
            failures = event.failedAttempts.map { (candidate, failure) -> failureInfo(candidate, failure.text) },
            notTried = event.notTried,
            skipped = event.skipped,
        )
    }

    private fun stepEvent(event: PipelineEvent.Step): StepEvent {
        val (id, step) = event.candidate.id to event.step.name
        return when (val status = event.status) {
            is StepStatus.Running -> StepEvent(id, step, "running", done = status.done, total = status.total)
            is StepStatus.Done -> StepEvent(id, step, "done", millis = status.millis, detail = status.detail)
            is StepStatus.Failed -> StepEvent(id, step, "failed", millis = status.millis, failure = status.failure.text)
        }
    }

    private fun info(candidate: PlayCandidate) = ReleaseInfo(candidate.id, candidate.title, candidate.indexer)

    private fun failureInfo(candidate: PlayCandidate, failure: String) = FailureInfo(info(candidate), failure)

    private fun trackInfo(track: ProbedTrack, playable: Boolean) =
        TrackInfo(track.label, track.language, track.default, playable)

    /** The ffprobe codec name behind a track's MIME type, as the browser's capabilities name it. */
    private fun codecOf(track: ProbedTrack): String? = MIME_CODECS[track.mime]

    private sealed interface Wanted {
        data class Movie(val request: MovieRequest) : Wanted
        data class Episode(val request: EpisodeRequest) : Wanted
    }

    private companion object {
        const val REASONS_SHOWN = 3

        /** Which season and episodes a file name holds, as release-finder reads release names. */
        val EPISODE_NAMES = EpisodeNames { name ->
            ReleaseParser.parse(name).let { EpisodeNumbers(it.seasons, it.episodes) }
        }

        val MIME_CODECS = mapOf(
            "audio/mp4a-latm" to "aac", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/opus" to "opus",
            "audio/flac" to "flac", "audio/mpeg" to "mp3",
        )
    }
}
