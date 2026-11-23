package io.skjaere.debridav.collection.indexer

import io.skjaere.newznab.Indexer
import io.skjaere.newznab.IndexerEndpoint
import io.skjaere.newznab.Newznab
import io.skjaere.newznab.NewznabException
import io.skjaere.newznab.UrlSecurity
import io.skjaere.newznab.UserAgentRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** A request the indexer settings refuse, in words the settings page shows. */
class IndexerRejected(message: String) : RuntimeException(message)

class NoSuchIndexer(id: Long) : RuntimeException("No indexer $id")

/**
 * The Newznab indexers the collection player searches. An API key is never returned, and a saved key only goes with an
 * edited address that is the same server ([IndexerEndpoint.takesKeyOf]): an edit pointing elsewhere, or from https to
 * plain http, has to give the key again.
 */
@Service
class IndexerService(
    private val repository: NewznabIndexerRepository,
    private val newznab: Newznab,
) {
    fun list(): List<IndexerDto> = repository.findAll().sortedBy { it.id }.map { it.toDto() }

    /** The indexers a search asks, in the order they were added. */
    fun indexers(): List<Indexer> = repository.findAll().sortedBy { it.id }.map { it.toIndexer() }

    @Transactional
    fun create(request: IndexerRequest): IndexerDto {
        val key = request.apiKey?.trim().orEmpty()
        if (key.isEmpty()) throw IndexerRejected("Enter the indexer's API key")
        check(request.name, request.url, request.userAgent)
        val now = Instant.now()
        val indexer = NewznabIndexer().apply {
            name = request.name.trim()
            url = request.url.trim()
            apiKey = key
            enabled = request.enabled
            userAgent = request.userAgent.trim()
            createdAt = now
            updatedAt = now
        }
        return repository.save(indexer).toDto()
    }

    @Transactional
    fun update(id: Long, request: IndexerRequest): IndexerDto {
        val indexer = repository.findById(id).orElseThrow { NoSuchIndexer(id) }
        check(request.name, request.url, request.userAgent)
        val key = request.apiKey?.trim().orEmpty().ifEmpty { savedKeyFor(indexer, request.url) }
        indexer.apply {
            name = request.name.trim()
            url = request.url.trim()
            apiKey = key
            enabled = request.enabled
            userAgent = request.userAgent.trim()
            updatedAt = Instant.now()
        }
        return repository.save(indexer).toDto()
    }

    @Transactional
    fun delete(id: Long) {
        if (!repository.existsById(id)) throw NoSuchIndexer(id)
        repository.deleteById(id)
    }

    /** Asks the indexer for its caps, fresh: whether the address and key work, and what it can search by. */
    suspend fun test(request: IndexerTestRequest): IndexerTestResultDto {
        checkAddress(request.url)
        checkUserAgent(request.userAgent)
        val key = request.apiKey?.trim().orEmpty().ifEmpty {
            val saved = request.id?.let { repository.findById(it).orElse(null) }
                ?: throw IndexerRejected("Enter the indexer's API key")
            savedKeyFor(saved, request.url)
        }
        val indexer = Indexer(
            id = "test-${request.id ?: "new"}",
            url = request.url.trim(),
            apiKey = key,
            userAgent = request.userAgent.trim(),
        )
        return try {
            val caps = newznab.caps(indexer, fresh = true)
            IndexerTestResultDto(
                success = true,
                message = "Connected",
                serverTitle = caps.serverTitle,
                searches = caps.searching.entries.sortedBy { it.key.ordinal }.map { (type, params) ->
                    if (params.isEmpty()) type.capsElement else "${type.capsElement}: ${params.joinToString(", ")}"
                },
            )
        } catch (e: NewznabException) {
            // The library's messages never hold the key.
            IndexerTestResultDto(success = false, message = e.error.message)
        }
    }

    /** [indexer]'s saved key, where it may go to [url]: the same server, and not from https to plain http. */
    private fun savedKeyFor(indexer: NewznabIndexer, url: String): String {
        val target = checkAddress(url)
        val saved = IndexerEndpoint.of(indexer.url)
        if (saved == null || !target.takesKeyOf(saved)) {
            throw IndexerRejected("Enter the API key again: the saved one belongs to another address")
        }
        return indexer.apiKey
    }

    private fun check(name: String, url: String, userAgent: String) {
        if (name.isBlank()) throw IndexerRejected("Give the indexer a name")
        checkAddress(url)
        checkUserAgent(userAgent)
    }

    private fun checkAddress(url: String): IndexerEndpoint =
        IndexerEndpoint.of(url.trim())
            ?: throw IndexerRejected("Enter the indexer's address, starting with https:// or http://")

    private fun checkUserAgent(userAgent: String) {
        when (UserAgentRule.problem(userAgent)) {
            UserAgentRule.Problem.Characters ->
                throw IndexerRejected("The User-Agent can only hold plain letters, digits and punctuation")
            UserAgentRule.Problem.TooLong ->
                throw IndexerRejected("The User-Agent can be at most ${UserAgentRule.MAX_LENGTH} characters")
            null -> Unit
        }
    }

    private fun NewznabIndexer.toDto() = IndexerDto(
        id = id!!,
        name = name,
        url = url,
        enabled = enabled,
        userAgent = userAgent,
        hasApiKey = apiKey.isNotEmpty(),
        urlSecurity = UrlSecurity.of(url).name,
    )
}
