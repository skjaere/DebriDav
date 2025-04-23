package io.skjaere.debridav.torrent

const val TORRENT_HASH_LENGTH = 40


data class TorrentHash(val hash: String) {
    init {
        require(hash.length == TORRENT_HASH_LENGTH) {
            "Hash must be 40 characters long"
        }
    }

}


data class Magnet(val hash: String)
