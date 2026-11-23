ALTER TABLE torrent ADD COLUMN last_verified TIMESTAMP;
ALTER TABLE torrent ADD COLUMN health_check_enqueued_at TIMESTAMP;

SELECT pgmq.create('torrent_health_check');
SELECT pgmq.create('torrent_health_repair');
