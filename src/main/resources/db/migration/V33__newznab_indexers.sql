-- The Newznab indexers the collection player searches for releases.
CREATE TABLE newznab_indexer
(
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT      NOT NULL,
    url        TEXT      NOT NULL,
    api_key    TEXT      NOT NULL,
    enabled    BOOLEAN   NOT NULL DEFAULT TRUE,
    -- Sent in place of debridav's own User-Agent when not empty.
    user_agent TEXT      NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
