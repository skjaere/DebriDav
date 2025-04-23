package io.skjaere.debridav.torrent

import com.dampcake.bencode.Bencode
import com.dampcake.bencode.Type
import io.ktor.util.decodeString
import io.skjaere.debridav.debrid.TorrentMagnet
import org.apache.commons.codec.digest.DigestUtils
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.nio.ByteBuffer

@Service
class TorrentToMagnetConverter {
    private val bencode = Bencode(true)

    fun convertTorrentToMagnet(torrent: ByteArray): TorrentMagnet {
        val decodedTorrent = bencode.decode(torrent, Type.DICTIONARY)
        val torrentInfo = (decodedTorrent["info"]!! as Map<String, Object>)
        val hash = bencode.encode(torrentInfo)
        val digest = DigestUtils.sha1Hex(hash)
        val xt = "urn:btih:$digest"

        val name = (torrentInfo["name"] as ByteBuffer).decodeString()
        val dn = URLEncoder.encode(
            name,
            Charsets.UTF_8.name()
        )
        val trackers =
            if (torrentInfo.containsKey("announce-list")) (decodedTorrent["announce-list"] as List<String>)
                .joinToString("&tr=") { URLEncoder.encode(it, Charsets.UTF_8.name()) }
            else URLEncoder.encode((decodedTorrent["announce"] as ByteBuffer).decodeString(), Charsets.UTF_8.name())


        return TorrentMagnet("magnet:?xt=$xt&dn=$dn&tr=$trackers")
    }
}
