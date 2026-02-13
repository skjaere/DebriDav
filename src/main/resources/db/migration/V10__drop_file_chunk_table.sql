-- Clean up large objects referenced by file_chunk blobs
SELECT lo_unlink(b.local_contents)
FROM blob b
WHERE b.id IN (SELECT blob_id FROM file_chunk WHERE blob_id IS NOT NULL)
  AND b.local_contents IS NOT NULL;

-- Delete blobs that were only used by file_chunk
DELETE FROM blob
WHERE id IN (SELECT blob_id FROM file_chunk WHERE blob_id IS NOT NULL);

-- Drop the foreign key constraint before dropping the table
ALTER TABLE file_chunk DROP CONSTRAINT IF EXISTS fk_filechunk_on_blob;

-- Drop the file_chunk table and its sequence
DROP TABLE IF EXISTS file_chunk;
DROP SEQUENCE IF EXISTS file_chunk_seq;
