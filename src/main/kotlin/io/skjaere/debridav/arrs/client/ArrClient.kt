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
     * Returns true iff at least one match was processed (path matched, file
     * deleted, search triggered). Returns false when there's no match in the arr
     * OR when the matches all point at different physical files (e.g. a duplicate
     * of the same release imported from a different download client).
     */
    suspend fun deleteFileAndSearch(name: String, expectedPaths: Set<String>): Boolean
}
