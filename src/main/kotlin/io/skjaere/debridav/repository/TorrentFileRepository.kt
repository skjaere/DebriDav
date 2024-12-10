package io.skjaere.debridav.repository

import io.skjaere.debridav.qbittorrent.TorrentFile
import org.springframework.data.repository.CrudRepository

interface TorrentFileRepository : CrudRepository<TorrentFile, Long>
