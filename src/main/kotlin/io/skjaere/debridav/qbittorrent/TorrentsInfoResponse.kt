package io.skjaere.debridav.qbittorrent

import com.fasterxml.jackson.annotation.JsonProperty

data class TorrentsInfoResponse(
    @JsonProperty("added_on")
    val addedOn: Int,
    @JsonProperty("amount_left")
    val amountLeft: Int,
    @JsonProperty("auto_tmm")
    val autoTmm: Boolean,
    val availability: Float,
    val category: String,
    val completed: Int,
    @JsonProperty("completion_id")
    val completionOn: Int,
    @JsonProperty("content_path")
    val contentPath: String,
    @JsonProperty("dl_limit")
    val dlLimit: Int,
    @JsonProperty("dlspeed")
    val dlSpeed: Int,
    val downloaded: Int,
    @JsonProperty("downloaded_session")
    val downloadedSession: Int,
    val eta: Int,
    @JsonProperty("f_l_piece_prio")
    val firstLastPiecePriority: Boolean,
    @JsonProperty("force_start")
    val forceStart: Boolean,
    val hash: String,
    @JsonProperty("last_activity")
    val lastActivity: Int,
    @JsonProperty("magnet_uri")
    val magnetUri: String,
    @JsonProperty("max_ration")
    val maxRatio: Float,
    @JsonProperty("max_seeding_time")
    val maxSeedingTime: Int,
    val name: String,
    @JsonProperty("num_complete")
    val numComplete: Int,
    @JsonProperty("num_incomplete")
    val numIncomplete: Int,
    @JsonProperty("num_leechs")
    val numLeeches: Int,
    @JsonProperty("num_seeds")
    val numSeeds: Int,
    val priority: Int,
    val progress: Float,
    val ratio: Float,
    @JsonProperty("ratio_limit")
    val ratioLimit: Float,
    @JsonProperty("save_path")
    val savePath: String,
    @JsonProperty("seeding_time")
    val seedingTime: Int,
    @JsonProperty("seeding_time_limit")
    val seedingTimeLimit: Int,
    @JsonProperty("seen_complete")
    val seenComplete: Int,
    @JsonProperty("seq_dl")
    val seqDl: Boolean,
    val size: Int,
    val state: String,
    @JsonProperty("super_seeding")
    val superSeeding: Boolean,
    val tags: String,
    @JsonProperty("time_active")
    val timeActive: Int,
    @JsonProperty("total_size")
    val totalSize: Int,
    val tracker: String,
    @JsonProperty("upl_limit")
    val upLimit: Int,
    val uploaded: Int,
    @JsonProperty("uploaded_session")
    val uploadedSession: Int,
    @JsonProperty("upspeed")
    val upSpeed: Int
) {
    companion object {
        fun ofTorrent(torrent: Torrent, downloadDir: String): TorrentsInfoResponse {
            return TorrentsInfoResponse(
                addedOn = torrent.created!!.toEpochMilli().toInt(),
                amountLeft = 0,
                autoTmm = false,
                availability = 1.0.toFloat(),
                category = torrent.category!!.name!!,
                completed = 1,
                completionOn = torrent.created!!.toEpochMilli().toInt(),
                contentPath = "$downloadDir${torrent.savePath}",
                dlLimit = 0,
                dlSpeed = 0,
                downloaded = 100,
                downloadedSession = 100,
                eta = 100,
                firstLastPiecePriority = false,
                forceStart = false,
                hash = torrent.hash!!,
                lastActivity = 100,
                magnetUri = "",
                maxRatio = 10.0.toFloat(),
                maxSeedingTime = 100,
                name = torrent.name!!,
                numComplete = 0,
                numIncomplete = 0,
                numLeeches = 0,
                numSeeds = 0,
                priority = 1,
                progress = 1.0.toFloat(),
                ratio = 1.0.toFloat(),
                ratioLimit = 1.0.toFloat(),
                savePath = downloadDir,
                seedingTime = 0,
                seedingTimeLimit = 0,
                seenComplete = 0,
                seqDl = false,
                size = 100,
                state = "pausedUP",
                superSeeding = false,
                tags = "",
                timeActive = 0,
                totalSize = 100,
                tracker = "",
                upLimit = 0,
                uploaded = 0,
                uploadedSession = 0,
                upSpeed = 0
            )
        }
    }
}
