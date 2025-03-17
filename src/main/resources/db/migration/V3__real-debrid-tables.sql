CREATE SEQUENCE IF NOT EXISTS real_debrid_download_entity_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS real_debrid_torrent_entity_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS real_debrid_torrent_file_seq START WITH 1 INCREMENT BY 50;


CREATE TABLE real_debrid_download_entity
(
    id          BIGINT NOT NULL,
    download_id VARCHAR(255),
    filename    VARCHAR(255),
    mime_type   VARCHAR(255),
    file_size   BIGINT,
    link        VARCHAR(255),
    host        VARCHAR(255),
    chunks      INTEGER,
    download    VARCHAR(255),
    streamable  INTEGER,
    generated   VARCHAR(255),
    CONSTRAINT pk_realdebriddownloadentity PRIMARY KEY (id)
);

CREATE TABLE real_debrid_torrent_entity
(
    id         BIGINT NOT NULL,
    torrent_id VARCHAR(255),
    name       VARCHAR(255),
    hash       VARCHAR(255),
    CONSTRAINT pk_realdebridtorrententity PRIMARY KEY (id)
);

CREATE TABLE real_debrid_torrent_entity_links
(
    real_debrid_torrent_entity_id BIGINT NOT NULL,
    links                         VARCHAR(255)
);

CREATE TABLE real_debrid_torrent_file
(
    id       BIGINT NOT NULL,
    file_id  INTEGER,
    path     VARCHAR(255),
    bytes    BIGINT,
    selected INTEGER,
    CONSTRAINT pk_realdebridtorrentfile PRIMARY KEY (id)
);

CREATE TABLE real_debrid_torrent_entity_files
(
    real_debrid_torrent_entity_id BIGINT NOT NULL,
    files_id                      BIGINT
);


ALTER TABLE real_debrid_torrent_entity_links
    ADD CONSTRAINT fk_realdebridtorrententity_links_on_real_debrid_torrent_entity FOREIGN KEY (real_debrid_torrent_entity_id) REFERENCES real_debrid_torrent_entity (id);
