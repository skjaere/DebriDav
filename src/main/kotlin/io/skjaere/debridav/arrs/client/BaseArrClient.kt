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

    /**
     * Trigger the arr's `RefreshMonitoredDownloads` command, which forces an immediate
     * scan of the configured download client(s) (debridav's qBittorrent / SABnzbd
     * emulation) instead of waiting for the next polling tick. Used right after debridav
     * adds new content so the arr starts importing it without the usual ~1 minute delay.
     *
     * Returns true on a 2xx response from the arr; false otherwise (logs the error).
     */
    suspend fun refreshMonitoredDownloads(): Boolean
}
