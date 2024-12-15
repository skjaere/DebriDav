package io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo

import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetListItem

data class SuccessfulDownloadInfo(
    val data: GetUsenetListItem
) : DownloadInfo
