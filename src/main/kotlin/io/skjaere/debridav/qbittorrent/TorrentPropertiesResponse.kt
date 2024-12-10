package io.skjaere.debridav.qbittorrent

import com.fasterxml.jackson.annotation.JsonProperty

data class TorrentPropertiesResponse(
    @JsonProperty("save_path")
    val savePath: String,
    @JsonProperty("creation_date")
    val creationDate: Int,
    @JsonProperty("piece_size")
    val pieceSize: Int,
    val comment: String,
    @JsonProperty("total_wasted")
    val totalWasted: Int,
    @JsonProperty("total_uploaded")
    val totalUploaded: Int,
    @JsonProperty("total_uploaded_session")
    val totalUploadedSession: Int,
    @JsonProperty("total_downloaded")
    val totalDownloaded: Int,
    @JsonProperty("total_downloaded_session")
    val totalDownloadedSession: Int,
    @JsonProperty("up_limit")
    val upLimit: Int,
    @JsonProperty("dl_limit")
    val dlLimit: Int,
    @JsonProperty("time_elapsed")
    val timeElapsed: Int,
    @JsonProperty("seeding_time")
    val seedingTime: Int,
    @JsonProperty("nb_connections")
    val nbConnections: Int,
    @JsonProperty("nb_connections_limit")
    val nbConnectionsLimit: Int,
    @JsonProperty("share_ratio")
    val shareRatio: Float,
    @JsonProperty("addition_date")
    val additionDate: Int,
    @JsonProperty("completion_date")
    val completionDate: Int,
    @JsonProperty("created_by")
    val createdBy: String,
    @JsonProperty("dl_speed_avg")
    val dlSpeedAvg: Int,
    @JsonProperty("dl_speed")
    val dlSpeed: Int,
    val eta: Int,
    @JsonProperty("last_seen")
    val lastSeen: Int,
    val peers: Int,
    @JsonProperty("peers_total")
    val peersTotal: Int,
    @JsonProperty("pieces_have")
    val piecesHave: Int,
    @JsonProperty("pieces_num")
    val piecesNum: Int,
    val reannounce: Int,
    val seeds: Int,
    @JsonProperty("seeds_total")
    val seedsTotal: Int,
    @JsonProperty("total_size")
    val totalSize: Int,
    @JsonProperty("up_speed_avg")
    val upSpeedAvg: Int,
    @JsonProperty("up_speed")
    val upSpeed: Int
) {
    companion object {
        fun ofTorrent(torrent: Torrent): TorrentPropertiesResponse {
            return TorrentPropertiesResponse(
                savePath = torrent.savePath!!,
                creationDate = torrent.created!!.toEpochMilli().toInt(),
                pieceSize = 1,
                comment = "",
                totalWasted = 1,
                totalUploaded = 1,
                totalUploadedSession = 1,
                totalDownloaded = 1,
                totalDownloadedSession = 1,
                upLimit = 0,
                dlLimit = 0,
                timeElapsed = 1,
                seedingTime = 0,
                nbConnections = 1,
                nbConnectionsLimit = 1,
                shareRatio = 1.0.toFloat(),
                additionDate = 1,
                completionDate = 1,
                createdBy = "",
                dlSpeedAvg = 1,
                dlSpeed = 1,
                eta = 1,
                lastSeen = 1,
                peers = 1,
                peersTotal = 1,
                piecesHave = 1,
                piecesNum = 1,
                reannounce = 1,
                seeds = 1,
                seedsTotal = 1,
                totalSize = 1,
                upSpeed = 1,
                upSpeedAvg = 1
            )
        }
    }
}
