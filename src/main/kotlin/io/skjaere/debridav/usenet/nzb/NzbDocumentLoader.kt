package io.skjaere.debridav.usenet.nzb

import io.skjaere.debridav.repository.NzbDocumentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Single transactional load + force-initialize for the file/segment graph attached
 * to a [NzbDocumentEntity]. Centralized so the wire-up rules sit in one place
 * instead of getting reinvented at every call site.
 *
 * **Why a service and not a default-method on the repository**: each Spring Data
 * repository call opens its own short-lived transaction. The repo can JOIN FETCH the
 * `files` bag, but [NzbFileEntity.segments] is `FetchMode.SUBSELECT` — it doesn't
 * fire until something *touches* a segments collection, which has to happen inside
 * the load transaction or it surfaces as `LazyInitializationException`. Wrapping
 * the load + the `entity.files.firstOrNull()?.segments?.size` "warm-up" in a single
 * `@Transactional` method on a real Spring service is the simplest way to give
 * callers a fully-detached, fully-initialized entity they can pass to
 * [toNzbDocument] without worrying about session scope.
 */
@Service
class NzbDocumentLoader(private val repository: NzbDocumentRepository) {

    @Transactional(readOnly = true)
    fun loadWithFilesAndSegments(id: Long): NzbDocumentEntity? {
        val entity = repository.findByIdWithFiles(id) ?: return null
        // Touch one segments collection to fire the SUBSELECT — Hibernate then loads
        // all segments for all loaded files in a single batched query, so subsequent
        // touches outside the transaction are safe.
        entity.files.firstOrNull()?.segments?.size
        return entity
    }
}
