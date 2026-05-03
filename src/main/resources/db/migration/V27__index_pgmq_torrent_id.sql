-- Expression indexes on the torrent-id payload extracted from the PGMQ message JSONB.
-- The dedup queries in PgmqHealthQueueRepository (queuedHealthCheckTorrentIds /
-- queuedRepairTorrentIds / isRepairQueued) all filter on (message->>'torrentId')::bigint;
-- without these indexes those queries are sequential scans, which is fine at low queue depth
-- but degrades during full-trigger sweeps that briefly land hundreds of messages on a queue.
CREATE INDEX IF NOT EXISTS idx_q_torrent_health_check_torrent_id
    ON pgmq.q_torrent_health_check (((message->>'torrentId')::bigint));

CREATE INDEX IF NOT EXISTS idx_q_torrent_health_repair_torrent_id
    ON pgmq.q_torrent_health_repair (((message->>'torrentId')::bigint));
