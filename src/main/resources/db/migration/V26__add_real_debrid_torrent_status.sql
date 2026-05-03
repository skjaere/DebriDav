-- Add `status` to the RD torrent mirror so the health check can rely on it as a
-- source of truth for "RD can currently serve this torrent" without hitting the API.
-- Backfilled to NULL — the next sync (default 12h) populates the column.
ALTER TABLE real_debrid_torrent_entity
    ADD COLUMN IF NOT EXISTS status VARCHAR(64);
