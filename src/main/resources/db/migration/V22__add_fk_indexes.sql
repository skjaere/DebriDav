-- Add indexes on FK columns that the application filters on regularly. Postgres
-- does not auto-index FK columns, so deletes from the parent table (e.g. removing
-- a Category) and joins against the child do full table scans without these.

CREATE INDEX IF NOT EXISTS idx_db_item_directory_id ON db_item (directory_id);
CREATE INDEX IF NOT EXISTS idx_torrent_category_id ON torrent (category_id);
CREATE INDEX IF NOT EXISTS idx_usenet_download_category_id ON usenet_download (category_id);
