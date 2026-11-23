package io.skjaere.debridav.collection.indexer

/** An indexer as the API shows it: never its key, only whether it has one. */
data class IndexerDto(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val userAgent: String,
    val hasApiKey: Boolean,
    /** How exposed the address leaves the key: Encrypted, LocalHttp or PublicHttp (warn about this one). */
    val urlSecurity: String,
)

/** A new indexer, or an edit. A blank [apiKey] on an edit keeps the saved one, where it may go to the new address. */
data class IndexerRequest(
    val name: String = "",
    val url: String = "",
    val apiKey: String? = null,
    val enabled: Boolean = true,
    val userAgent: String = "",
)

/** An indexer to test before or after saving it: [id]'s saved key stands in for a blank [apiKey]. */
data class IndexerTestRequest(
    val id: Long? = null,
    val url: String = "",
    val apiKey: String? = null,
    val userAgent: String = "",
)

data class IndexerTestResultDto(
    val success: Boolean,
    val message: String,
    val serverTitle: String? = null,
    /** The searches the indexer offers, with their parameters: "tv-search: q, tvdbid, season, ep". */
    val searches: List<String> = emptyList(),
)
