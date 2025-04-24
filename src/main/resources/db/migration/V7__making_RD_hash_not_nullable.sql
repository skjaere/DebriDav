DELETE
FROM real_debrid_torrent_entity
WHERE hash is null;
ALTER TABLE real_debrid_torrent_entity
    ALTER COLUMN hash SET NOT NULL;