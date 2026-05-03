-- Backfill the relational tables added in V29 from the existing nzb_document.files
-- JSONB, then drop the JSONB column. Pure SQL — `jsonb_array_elements WITH ORDINALITY`
-- streams the unnest server-side without materializing the whole library in JVM heap,
-- so this scales to any library size on the resource budget the database already has.
--
-- A row-count assertion fires before the column drop; if it trips, the entire
-- migration aborts and the JSONB column survives intact for a rerun.

-- 1. Backfill files. Array ordinality gives the position-in-document; we subtract 1
--    so file_index is 0-based to match the JPA mapping.
INSERT INTO nzb_file (nzb_document_id, file_index, yenc_size, yenc_part_end)
SELECT
    nd.id,
    (f.pos - 1)::int,
    (f.obj->>'yencSize')::bigint,
    -- yencPartEnd is nullable in NzbFileJson; Jackson serializes null as JSON null,
    -- which `->>` returns as a SQL NULL — no special handling needed.
    (f.obj->>'yencPartEnd')::bigint
FROM nzb_document nd
CROSS JOIN LATERAL jsonb_array_elements(nd.files) WITH ORDINALITY AS f(obj, pos);

-- 2. Backfill segments. Re-unnest both levels so we know which file each segment
--    belongs to, then join back to the just-inserted nzb_file via the
--    (document_id, file_index) composite to recover its surrogate id.
INSERT INTO nzb_segment (nzb_file_id, segment_number, article_id, bytes)
SELECT
    nf.id,
    (s.obj->>'number')::int,
    s.obj->>'articleId',
    (s.obj->>'bytes')::bigint
FROM nzb_document nd
CROSS JOIN LATERAL jsonb_array_elements(nd.files) WITH ORDINALITY AS f(obj, pos)
CROSS JOIN LATERAL jsonb_array_elements(f.obj->'segments') AS s(obj)
JOIN nzb_file nf
  ON nf.nzb_document_id = nd.id
 AND nf.file_index = (f.pos - 1)::int;

-- 3. Verify segment counts match. If the backfill missed anything (malformed JSONB,
--    null where a value was expected, etc.) the assertion aborts the transaction
--    and rolls back the inserts above — the JSONB column is left in place for
--    diagnosis and a corrected rerun.
DO $$
DECLARE
    jsonb_segments      bigint;
    relational_segments bigint;
BEGIN
    SELECT COALESCE(SUM(jsonb_array_length(f.obj->'segments')), 0)
    INTO jsonb_segments
    FROM nzb_document nd, jsonb_array_elements(nd.files) AS f(obj);

    SELECT COUNT(*) INTO relational_segments FROM nzb_segment;

    IF jsonb_segments <> relational_segments THEN
        RAISE EXCEPTION
            'V30 backfill mismatch: % segments in JSONB, % rows in nzb_segment',
            jsonb_segments, relational_segments;
    END IF;
END $$;

-- 4. Drop the source JSONB column. From this point forward the entity loses its
--    `files: List<NzbFileJson>` field and gains `@OneToMany(LAZY) files` of
--    NzbFileEntity — see the V30 commit for the matching code change.
ALTER TABLE nzb_document DROP COLUMN files;
