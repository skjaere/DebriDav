package io.skjaere.debridav.arrs.client

import io.ktor.client.statement.HttpResponse

sealed interface BaseArrClient {
    suspend fun parse(itemName: String): HttpResponse

    /** Mark a download as failed/blocklisted in this arr's history. Returns true if this
     *  arr had a history record matching [downloadId] and we successfully marked it failed,
     *  false if the arr has no record of this download or if the API call to mark it failed
     *  was unsuccessful. The Boolean is used by [io.skjaere.debridav.arrs.ArrService] to
     *  short-circuit fanout once an arr has handled the call.
     */
    suspend fun blocklist(downloadId: String): Boolean
}
