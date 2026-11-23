package io.skjaere.debridav.collection.play

import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.enrichment.VerificationResult
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.nzb.FilenameResolver
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbParser
import io.skjaere.nzbstreamer.play.AttemptFailure
import io.skjaere.nzbstreamer.play.AttemptSteps
import io.skjaere.nzbstreamer.play.FetchException
import io.skjaere.nzbstreamer.play.FilePicker
import io.skjaere.nzbstreamer.play.FileListing
import io.skjaere.nzbstreamer.play.ListedFile
import io.skjaere.nzbstreamer.play.NzbSource
import io.skjaere.nzbstreamer.play.PipelineEvent
import io.skjaere.nzbstreamer.play.PipelineStep
import io.skjaere.nzbstreamer.play.PlayCandidate
import io.skjaere.nzbstreamer.play.PlaySource
import io.skjaere.nzbstreamer.play.PlaybackTarget
import io.skjaere.nzbstreamer.play.ProbeResult
import io.skjaere.nzbstreamer.play.StepResult
import io.skjaere.nzbstreamer.play.StepStatus
import io.skjaere.nzbstreamer.play.UsenetError
import io.skjaere.nzbstreamer.play.discCopy
import io.skjaere.nzbstreamer.play.toUsenetError
import io.skjaere.nzbstreamer.play.unreadableFailure
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.nzbstreamer.stream.toSplits
import org.slf4j.LoggerFactory
import java.util.Locale

/** One of a prepared release's files, as the play loop lists it. */
class PreparedFile(override val index: Int, val streamable: StreamableFile) : ListedFile {
    override val path: String get() = streamable.path
    override val size: Long get() = streamable.totalSize
}

/** A release nzb-streamer prepared in memory, listed for the play loop to pick its file. */
class PreparedListing(override val id: String, override val title: String, override val files: List<PreparedFile>) :
    FileListing<PreparedFile>

/**
 * The file a play attempt opened. The probe [keep]s it to read it, which makes it the session's release, streamed to
 * ffprobe and FFmpeg at [uri]; [drop] lets go of it when the release didn't play.
 */
class SessionTarget(
    override val candidate: PlayCandidate,
    override val listing: PreparedListing,
    override val file: PreparedFile,
    val release: OpenRelease,
    private val session: PlaySession,
    override val uri: String,
) : PlaybackTarget {
    override suspend fun keep() = session.open(release)

    override suspend fun drop() = session.letGo(release)
}

/**
 * Usenet releases played by debridav, for the play loop: fetch the NZB from its indexer ([nzbs]), STAT a sample of its
 * articles, prepare it with nzb-streamer, in memory, and pick the file. A release that is all the one file to play (a
 * movie, an episode on its own) is checked before it is opened, which costs more than the check; any other (a season
 * pack) once its file is picked, on that file's volumes only, so a gap in another episode doesn't reject it. Extras
 * (PAR2, NFO, ...) are never checked. A login or connection problem stops every Usenet release
 * ([AttemptFailure.route]).
 */
class DebridavUsenetSource(
    private val nzbs: NzbSource,
    private val nzbStreamer: NzbStreamer,
    private val session: PlaySession,
    /** Where this session's release is streamed from: ffprobe and FFmpeg read it there. */
    private val sourceUri: String,
    private val samples: Int = AVAILABILITY_SAMPLES,
) : PlaySource<SessionTarget> {
    private val log = LoggerFactory.getLogger(DebridavUsenetSource::class.java)

    override val steps: List<PipelineStep> = STEPS

    override suspend fun AttemptSteps.attempt(candidate: PlayCandidate, pickFile: FilePicker): SessionTarget {
        val fetched = step(candidate, PipelineStep.FetchNzb) {
            try {
                nzbs.fetchWithPassword(candidate).let { StepResult.Ok(it, sizeOf(it.bytes.size.toLong())) }
            } catch (e: FetchException) {
                StepResult.Fail(
                    AttemptFailure.FetchFailed(e.reason, PlayCandidate.USENET.takeIf { e.stopsAll }, e.blocksIndexer),
                )
            }
        }
        val nzb = guarded { NzbParser.parse(fetched.bytes) }
        val whole = pickFile.takesWhole(candidate.title)
        if (whole) checkAvailability(candidate) { withoutExtras(nzb) }
        val opened = step(candidate, PipelineStep.Open) { watch ->
            // The NZB's own password wins; the indexer's is for an NZB without one.
            val password = fetched.password?.takeIf { nzb.password == null }
            when (val result = nzbStreamer.prepare(fetched.bytes, password)) {
                is PrepareResult.Success -> {
                    val files = nzbStreamer.resolveStreamableFiles(result.metadata)
                    val listing = PreparedListing(candidate.id, candidate.title, files.mapIndexed(::PreparedFile))
                    listing.discCopy()?.let { return@step StepResult.Fail(it) }
                    wholeRelease = whole || listing.oneVideo
                    val file = pickFile.pick(listing)
                        ?: chosen(candidate, listing, pickFile, watch, io.skjaere.nzbstreamer.play.FileChooser.None)
                        ?: return@step StepResult.Fail(pickFile.noMatchIn(listing))
                    StepResult.Ok(Triple(result.metadata, listing, file), file.name)
                }
                is PrepareResult.Encrypted -> StepResult.Fail(AttemptFailure.Passworded)
                is PrepareResult.MissingArticles -> StepResult.Fail(AttemptFailure.MissingArticles(result.message))
                is PrepareResult.UnsupportedArchive -> StepResult.Fail(AttemptFailure.Unsupported(result.message))
                is PrepareResult.Failure -> StepResult.Fail(classify(result.cause.toUsenetError()))
            }
        }
        val (metadata, listing, file) = opened
        if (!whole) {
            checkAvailability(candidate) {
                val volumes = metadata.orderedArchiveNzb.files
                val used = file.streamable.toSplits(metadata.orderedArchiveNzb).map { it.volumeIndex }.distinct()
                NzbDocument(used.map { volumes[it] })
            }
        }
        val release = OpenRelease(candidate.title, metadata, file.streamable)
        return SessionTarget(candidate, listing, file, release, session, sourceUri)
    }

    override suspend fun AttemptSteps.probeFailed(failed: ProbeResult.Failed, target: SessionTarget): AttemptFailure =
        unreadableFailure(failed, target.file.name) ?: classify(failed.error)

    override fun failed(error: Exception): AttemptFailure = classify(error.toUsenetError())

    /** STATs a sample of what [articles] gives, reporting progress at most every 100 ms. */
    private suspend fun AttemptSteps.checkAvailability(candidate: PlayCandidate, articles: () -> NzbDocument) {
        step(candidate, PipelineStep.CheckAvailability) {
            var lastSent = 0L
            val result = guarded {
                nzbStreamer.verifySample(articles(), samples) { done, total ->
                    val now = System.nanoTime()
                    if (done == total || now - lastSent > PROGRESS_INTERVAL_NANOS) {
                        lastSent = now
                        val running = StepStatus.Running(done, total)
                        trySend(PipelineEvent.Step(candidate, PipelineStep.CheckAvailability, running))
                    }
                }
            }
            when (result) {
                VerificationResult.Success -> StepResult.Ok(Unit)
                is VerificationResult.MissingArticles -> StepResult.Fail(
                    AttemptFailure.MissingArticles(result.message, result.missingCount, result.answeredCount),
                )
                is VerificationResult.Failure -> StepResult.Fail(classify(result.cause.toUsenetError()))
            }.also { log.info("play: {} availability: {}", named(candidate), result::class.simpleName) }
        }
    }

    /** What [error] fails a release as: a login or connection problem hits every Usenet release alike. */
    private fun classify(error: UsenetError): AttemptFailure = when (error) {
        is UsenetError.AuthFailed, is UsenetError.ConnectionFailed, UsenetError.NotConfigured ->
            AttemptFailure.Provider(error)
        is UsenetError.MissingArticle -> AttemptFailure.MissingArticles(error.detail.orEmpty())
        else -> AttemptFailure.Failed(error)
    }

    companion object {
        /** A Usenet release's steps, in their order, as the progress screen lists them. */
        val STEPS = listOf(
            PipelineStep.FetchNzb,
            PipelineStep.CheckAvailability,
            PipelineStep.Open,
            PipelineStep.ProbeTracks,
        )

        /** As NZStreamer: enough to catch a release with a share of its articles gone. */
        const val AVAILABILITY_SAMPLES = 256

        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

        private val EXTRAS = Regex("""\.(par2|nfo|sfv|srr|srt|sub|idx|txt|jpe?g|png|nzb)$""", RegexOption.IGNORE_CASE)

        /** [nzb] without its extras (PAR2, NFO, ...), whose articles nothing that plays needs. */
        fun withoutExtras(nzb: NzbDocument): NzbDocument = nzb.copy(
            files = nzb.files.filterNot { file ->
                FilenameResolver.extractSubjectFilename(file.subject)?.let(EXTRAS::containsMatchIn) == true
            }.ifEmpty { nzb.files },
        )

        private fun sizeOf(bytes: Long): String =
            if (bytes < KIB) "$bytes B" else String.format(Locale.ROOT, "%.0f KB", bytes / KIB.toDouble())

        private const val KIB = 1024L
    }
}
