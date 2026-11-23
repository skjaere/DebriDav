-- V24: schema preparation for soft-delete + magnet-on-Torrent.
--
-- Adds the `magnet` column to the torrent table. Currently the magnet URI lives
-- per-file on debrid_cached_torrent_content.magnet, which is a denormalisation
-- forced by hard-deletes losing the natural Torrent home. V25 (a Java migration)
-- backfills this column for existing rows and reconstructs missing Torrent rows
-- for orphan content. V26 will drop the redundant per-file column once readers
-- have migrated.
--
-- Schema-only change — Status.DELETED already exists in the Kotlin enum, the
-- markTorrentAsDeleted query already exists in TorrentRepository. The behavioural
-- switch (deleteTorrentByHash → soft-delete, qbit reads filter to LIVE) lives in
-- the same release as this migration.

ALTER TABLE torrent ADD COLUMN magnet VARCHAR(2048);
