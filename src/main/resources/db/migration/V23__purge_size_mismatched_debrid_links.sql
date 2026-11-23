-- Prior releases (≤ v0.11.0 / 1.0-rc11) had a "if response.cachedFiles.size == 1, return it"
-- short-circuit in DebridLinkService.mapResponseToDebridFile that silently returned the only
-- CachedFile in a partial provider response WITHOUT checking that it actually matched the
-- requested file. When a season-pack response shrank to a single entry (because per-file
-- unrestricts on the other episodes had failed with hoster_unavailable), that single
-- CachedFile's link / path / size got persisted onto whichever file was being requested.
-- The visible symptom was "downloading episode N gives you the bytes of episode M, truncated
-- at N's expected length, with no moov atom" — the wrong content under the right filename.
--
-- The size in the persisted CachedFile is a reliable poison-detector: a CachedFile whose
-- declared size disagrees with its contents row's own size is unambiguously bogus. This
-- migration drops every such entry from debrid_links across all three subtype tables.
-- Affected files will re-resolve through the corrected code path on next access.

UPDATE debrid_cached_torrent_content c
SET debrid_links = (
    SELECT COALESCE(jsonb_agg(elem ORDER BY ord), '[]'::jsonb)
    FROM jsonb_array_elements(c.debrid_links) WITH ORDINALITY AS x(elem, ord)
    WHERE NOT (
        x.elem->>'@type' = 'CachedFile'
        AND (x.elem->>'size')::bigint <> c.size
    )
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(c.debrid_links) elem
    WHERE elem->>'@type' = 'CachedFile'
      AND (elem->>'size')::bigint <> c.size
);

UPDATE debrid_cached_usenet_release_content c
SET debrid_links = (
    SELECT COALESCE(jsonb_agg(elem ORDER BY ord), '[]'::jsonb)
    FROM jsonb_array_elements(c.debrid_links) WITH ORDINALITY AS x(elem, ord)
    WHERE NOT (
        x.elem->>'@type' = 'CachedFile'
        AND (x.elem->>'size')::bigint <> c.size
    )
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(c.debrid_links) elem
    WHERE elem->>'@type' = 'CachedFile'
      AND (elem->>'size')::bigint <> c.size
);

UPDATE nzb_contents c
SET debrid_links = (
    SELECT COALESCE(jsonb_agg(elem ORDER BY ord), '[]'::jsonb)
    FROM jsonb_array_elements(c.debrid_links) WITH ORDINALITY AS x(elem, ord)
    WHERE NOT (
        x.elem->>'@type' = 'CachedFile'
        AND (x.elem->>'size')::bigint <> c.size
    )
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(c.debrid_links) elem
    WHERE elem->>'@type' = 'CachedFile'
      AND (elem->>'size')::bigint <> c.size
);
