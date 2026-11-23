package io.skjaere.debridav.arrs.client

interface ArrClient : BaseArrClient {
    suspend fun getItemIdFromName(name: String): Long?
    fun getCategory(): String

    /**
     * Find episodes/movies matching [name] in the arr; for each match that already
     * has an imported file, compare the arr-side file path (via [ArrPathMatcher])
     * to the set of paths in [expectedPaths]. Only delete and trigger a new search
     * for matches whose file path equals one of the expected paths.
     *
     * The set lets a single parse call cover a multi-file NZB release (one entry
     * per file in the unhealthy debridav copy). For the torrent case the set is
     * typically a singleton (one file per repair lookup).
     *
     * Returns [DeleteAndSearchResult.Replaced] when at least one match was processed (path
     * matched, file deleted, search triggered), [DeleteAndSearchResult.NotImported] when the
     * arr has no imported file for [name], and [DeleteAndSearchResult.ImportedElsewhere] when
     * its imported files are at none of [expectedPaths].
     */
    suspend fun deleteFileAndSearch(name: String, expectedPaths: Set<String>): DeleteAndSearchResult
}

/** What asking the arrs to replace an unhealthy file came to. */
sealed interface DeleteAndSearchResult {
    /** An arr's file was at an expected path: the arr deleted it and searched again. */
    data object Replaced : DeleteAndSearchResult

    /** No arr has this release imported (no match, or a match not imported yet). */
    data object NotImported : DeleteAndSearchResult

    /**
     * An arr has this release imported, but at none of the expected paths: either debridav's
     * file seen through a different mount than `debridav.mount-path`, or another copy. Empty
     * [arrPaths] means the arr's file path couldn't be read.
     */
    data class ImportedElsewhere(val arrPaths: List<String>) : DeleteAndSearchResult

    /** An arr couldn't be asked, so it may still use the file. */
    data class Failed(val reason: String?) : DeleteAndSearchResult
}
