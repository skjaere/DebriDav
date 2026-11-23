SELECT pgmq.create('nzb_import_dlq');
SELECT pgmq.create('nzb_health_check_dlq');
SELECT pgmq.create('nzb_health_repair_dlq');
SELECT pgmq.create('torrent_health_check_dlq');
SELECT pgmq.create('torrent_health_repair_dlq');
