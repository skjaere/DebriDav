package io.skjaere.debridav.health

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class PgmqHealthQueueRepository(private val jdbc: JdbcTemplate) {

    fun getPendingHealthChecks(): List<HealthQueueItemDto> {
        val nzbItems = getPendingNzb("nzb_health_check", false)
        val torrentItems = getPendingTorrent("torrent_health_check", false)
        return nzbItems + torrentItems
    }

    fun getPendingRepairs(): List<HealthQueueItemDto> {
        val nzbItems = getPendingNzb("nzb_health_repair", true)
        val torrentItems = getPendingTorrent("torrent_health_repair", true)
        return nzbItems + torrentItems
    }

    fun getHealthCheckHistory(page: Int, size: Int, search: String): HealthQueueHistoryResponse {
        val nzbHistory = getArchivedNzb("nzb_health_check", false, search)
        val torrentHistory = getArchivedTorrent("torrent_health_check", false, search)
        return paginateCombined(nzbHistory + torrentHistory, page, size)
    }

    fun getRepairHistory(page: Int, size: Int, search: String): HealthQueueHistoryResponse {
        val nzbHistory = getArchivedNzb("nzb_health_repair", true, search)
        val torrentHistory = getArchivedTorrent("torrent_health_repair", true, search)
        return paginateCombined(nzbHistory + torrentHistory, page, size)
    }

    private fun getPendingNzb(queueName: String, hasMessage: Boolean): List<HealthQueueItemDto> {
        if (!queueExists(queueName)) return emptyList()
        val sql = """
            SELECT q.msg_id,
                   (q.message->>'nzbDocumentId')::bigint AS document_id,
                   ${if (hasMessage) "q.message->>'message' AS repair_message," else ""}
                   d.name AS doc_name,
                   d.category,
                   q.read_ct,
                   q.enqueued_at,
                   q.vt AS last_read_at
            FROM pgmq.q_$queueName q
            LEFT JOIN nzb_document d ON d.id = (q.message->>'nzbDocumentId')::bigint
            ORDER BY q.msg_id
        """.trimIndent()

        return jdbc.query(sql) { rs, _ ->
            mapPendingRow(rs, hasMessage, "NZB")
        }
    }

    private fun getPendingTorrent(queueName: String, hasMessage: Boolean): List<HealthQueueItemDto> {
        if (!queueExists(queueName)) return emptyList()
        val sql = """
            SELECT q.msg_id,
                   (q.message->>'torrentId')::bigint AS document_id,
                   ${if (hasMessage) "q.message->>'message' AS repair_message," else ""}
                   t.name AS doc_name,
                   c.name AS category,
                   q.read_ct,
                   q.enqueued_at,
                   q.vt AS last_read_at
            FROM pgmq.q_$queueName q
            LEFT JOIN torrent t ON t.id = (q.message->>'torrentId')::bigint
            LEFT JOIN category c ON c.id = t.category_id
            ORDER BY q.msg_id
        """.trimIndent()

        return jdbc.query(sql) { rs, _ ->
            mapPendingRow(rs, hasMessage, "TORRENT")
        }
    }

    private fun getArchivedNzb(
        queueName: String,
        hasMessage: Boolean,
        search: String
    ): List<HealthQueueItemDto> {
        if (!queueExists(queueName, archived = true)) return emptyList()
        val searchClause = if (search.isNotBlank()) "AND LOWER(d.name) LIKE ?" else ""
        val outcomeJoin = if (hasMessage) {
            "LEFT JOIN repair_outcome ro ON ro.queue_name = '$queueName' AND ro.msg_id = q.msg_id"
        } else ""
        val outcomeSelect = if (hasMessage) ", ro.action AS repair_action" else ""
        val sql = """
            SELECT q.msg_id,
                   (q.message->>'nzbDocumentId')::bigint AS document_id,
                   ${if (hasMessage) "q.message->>'message' AS repair_message," else ""}
                   d.name AS doc_name,
                   d.category,
                   q.read_ct,
                   q.enqueued_at,
                   q.vt AS last_read_at,
                   q.archived_at
                   $outcomeSelect
            FROM pgmq.a_$queueName q
            LEFT JOIN nzb_document d ON d.id = (q.message->>'nzbDocumentId')::bigint
            $outcomeJoin
            WHERE 1=1 $searchClause
            ORDER BY q.archived_at DESC
        """.trimIndent()

        val searchParam = if (search.isNotBlank()) "%${search.lowercase()}%" else null
        return if (searchParam != null) {
            jdbc.query(sql, { rs, _ -> mapArchivedRow(rs, hasMessage, "NZB") }, searchParam)
        } else {
            jdbc.query(sql) { rs, _ -> mapArchivedRow(rs, hasMessage, "NZB") }
        }
    }

    private fun getArchivedTorrent(
        queueName: String,
        hasMessage: Boolean,
        search: String
    ): List<HealthQueueItemDto> {
        if (!queueExists(queueName, archived = true)) return emptyList()
        val searchClause = if (search.isNotBlank()) "AND LOWER(t.name) LIKE ?" else ""
        val outcomeJoin = if (hasMessage) {
            "LEFT JOIN repair_outcome ro ON ro.queue_name = '$queueName' AND ro.msg_id = q.msg_id"
        } else ""
        val outcomeSelect = if (hasMessage) ", ro.action AS repair_action" else ""
        val sql = """
            SELECT q.msg_id,
                   (q.message->>'torrentId')::bigint AS document_id,
                   ${if (hasMessage) "q.message->>'message' AS repair_message," else ""}
                   t.name AS doc_name,
                   c.name AS category,
                   q.read_ct,
                   q.enqueued_at,
                   q.vt AS last_read_at,
                   q.archived_at
                   $outcomeSelect
            FROM pgmq.a_$queueName q
            LEFT JOIN torrent t ON t.id = (q.message->>'torrentId')::bigint
            LEFT JOIN category c ON c.id = t.category_id
            $outcomeJoin
            WHERE 1=1 $searchClause
            ORDER BY q.archived_at DESC
        """.trimIndent()

        val searchParam = if (search.isNotBlank()) "%${search.lowercase()}%" else null
        return if (searchParam != null) {
            jdbc.query(sql, { rs, _ -> mapArchivedRow(rs, hasMessage, "TORRENT") }, searchParam)
        } else {
            jdbc.query(sql) { rs, _ -> mapArchivedRow(rs, hasMessage, "TORRENT") }
        }
    }

    private fun paginateCombined(
        all: List<HealthQueueItemDto>,
        page: Int,
        size: Int
    ): HealthQueueHistoryResponse {
        val sorted = all.sortedByDescending { it.archivedAt }
        val totalElements = sorted.size.toLong()
        val totalPages = if (size > 0) ((totalElements + size - 1) / size).toInt() else 0
        val offset = page * size
        val items = sorted.drop(offset).take(size)

        return HealthQueueHistoryResponse(
            content = items,
            page = page,
            size = size,
            totalElements = totalElements,
            totalPages = totalPages,
            last = page >= totalPages - 1
        )
    }

    private fun queueExists(queueName: String, archived: Boolean = false): Boolean {
        val prefix = if (archived) "a_" else "q_"
        return try {
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'pgmq' AND table_name = ?)",
                Boolean::class.java,
                "$prefix$queueName"
            ) ?: false
        } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
            false
        }
    }

    private fun mapPendingRow(rs: ResultSet, hasMessage: Boolean, type: String): HealthQueueItemDto {
        return HealthQueueItemDto(
            msgId = rs.getLong("msg_id"),
            documentId = rs.getLong("document_id"),
            name = rs.getString("doc_name"),
            category = rs.getString("category"),
            type = type,
            readCount = rs.getInt("read_ct"),
            enqueuedAt = rs.getTimestamp("enqueued_at")?.toInstant(),
            lastReadAt = rs.getTimestamp("last_read_at")?.toInstant(),
            archivedAt = null,
            message = if (hasMessage) rs.getString("repair_message") else null
        )
    }

    private fun mapArchivedRow(rs: ResultSet, hasMessage: Boolean, type: String): HealthQueueItemDto {
        return HealthQueueItemDto(
            msgId = rs.getLong("msg_id"),
            documentId = rs.getLong("document_id"),
            name = rs.getString("doc_name"),
            category = rs.getString("category"),
            type = type,
            readCount = rs.getInt("read_ct"),
            enqueuedAt = rs.getTimestamp("enqueued_at")?.toInstant(),
            lastReadAt = rs.getTimestamp("last_read_at")?.toInstant(),
            archivedAt = rs.getTimestamp("archived_at")?.toInstant(),
            message = if (hasMessage) rs.getString("repair_message") else null,
            action = if (hasMessage) rs.getString("repair_action") else null
        )
    }
}
