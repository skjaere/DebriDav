CREATE EXTENSION IF NOT EXISTS LTREE;

CREATE SEQUENCE IF NOT EXISTS blob_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS category_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS db_item_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS debrid_file_contents_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS debrid_file_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS import_registry_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS torrent_seq START WITH 1 INCREMENT BY 50;

CREATE SEQUENCE IF NOT EXISTS usenet_download_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE blob
(
    id             BIGINT NOT NULL,
    local_contents OID,
    CONSTRAINT blob_pkey PRIMARY KEY (id)
);

CREATE TABLE cached_file
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    link         VARCHAR(2048),
    mime_type    VARCHAR(255),
    path         VARCHAR(2048),
    size         BIGINT,
    CONSTRAINT cached_file_pkey PRIMARY KEY (id)
);

CREATE TABLE cached_file_params
(
    cached_file_id BIGINT       NOT NULL,
    value          VARCHAR(255),
    key            VARCHAR(255) NOT NULL,
    CONSTRAINT cached_file_params_pkey PRIMARY KEY (cached_file_id, key)
);

CREATE TABLE category
(
    id            BIGINT NOT NULL,
    download_path VARCHAR(255),
    name          VARCHAR(255),
    CONSTRAINT category_pkey PRIMARY KEY (id)
);

CREATE TABLE client_error
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    CONSTRAINT client_error_pkey PRIMARY KEY (id)
);

CREATE TABLE db_item
(
    db_item_type            VARCHAR(31) NOT NULL,
    id                      BIGINT      NOT NULL,
    last_modified           BIGINT,
    mime_type               VARCHAR(255),
    name                    VARCHAR(255),
    size                    BIGINT,
    path                    LTREE,
    directory_id            BIGINT,
    blob_id                 BIGINT,
    debrid_file_contents_id BIGINT,
    CONSTRAINT db_item_pkey PRIMARY KEY (id)
);

CREATE TABLE debrid_cached_torrent_content
(
    id            BIGINT NOT NULL,
    mime_type     VARCHAR(255),
    modified      BIGINT,
    original_path VARCHAR(255),
    size          BIGINT,
    magnet        VARCHAR(2048),
    CONSTRAINT debrid_cached_torrent_content_pkey PRIMARY KEY (id)
);

CREATE TABLE debrid_cached_usenet_release_content
(
    id            BIGINT NOT NULL,
    mime_type     VARCHAR(255),
    modified      BIGINT,
    original_path VARCHAR(255),
    size          BIGINT,
    release_name  VARCHAR(2048),
    CONSTRAINT debrid_cached_usenet_release_content_pkey PRIMARY KEY (id)
);

CREATE TABLE debrid_file_contents_debrid_links
(
    debrid_file_contents_id BIGINT NOT NULL,
    debrid_links_id         BIGINT NOT NULL
);

CREATE TABLE debrid_usenet_contents
(
    id                 BIGINT NOT NULL,
    mime_type          VARCHAR(255),
    modified           BIGINT,
    original_path      VARCHAR(255),
    size               BIGINT,
    hash               VARCHAR(255),
    nzb_file_location  VARCHAR(255),
    usenet_download_id BIGINT,
    CONSTRAINT debrid_usenet_contents_pkey PRIMARY KEY (id)
);

CREATE TABLE import_registry
(
    id   BIGINT NOT NULL,
    path VARCHAR(2048),
    CONSTRAINT import_registry_pkey PRIMARY KEY (id)
);

CREATE TABLE missing_file
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    CONSTRAINT missing_file_pkey PRIMARY KEY (id)
);

CREATE TABLE network_error
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    CONSTRAINT network_error_pkey PRIMARY KEY (id)
);

CREATE TABLE provider_error
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    CONSTRAINT provider_error_pkey PRIMARY KEY (id)
);

CREATE TABLE torrent
(
    id          BIGINT NOT NULL,
    created     TIMESTAMP WITHOUT TIME ZONE,
    hash        VARCHAR(255),
    name        VARCHAR(255),
    save_path   VARCHAR(255),
    status      SMALLINT,
    category_id BIGINT,
    CONSTRAINT torrent_pkey PRIMARY KEY (id)
);

CREATE TABLE torrent_files
(
    torrent_id BIGINT NOT NULL,
    files_id   BIGINT NOT NULL
);

CREATE TABLE unknown_error
(
    id           BIGINT NOT NULL,
    last_checked BIGINT,
    provider     SMALLINT,
    CONSTRAINT unknown_error_pkey PRIMARY KEY (id)
);

CREATE TABLE usenet_download
(
    id                BIGINT NOT NULL,
    name              VARCHAR(255),
    percent_completed DOUBLE PRECISION,
    size              BIGINT,
    status            SMALLINT,
    storage_path      VARCHAR(255),
    category_id       BIGINT,
    CONSTRAINT usenet_download_pkey PRIMARY KEY (id)
);

CREATE TABLE usenet_download_debrid_files
(
    usenet_download_id BIGINT NOT NULL,
    debrid_files_id    BIGINT NOT NULL
);

ALTER TABLE torrent_files
    ADD CONSTRAINT uk5e3unjol4q8wvbnk3bpkqfmx2 UNIQUE (files_id);

ALTER TABLE debrid_file_contents_debrid_links
    ADD CONSTRAINT uk870cyr9jqi9mkmg2k7bapdt10 UNIQUE (debrid_links_id);

ALTER TABLE db_item
    ADD CONSTRAINT ukarv59hk02a9a93ouiso5haf97 UNIQUE (debrid_file_contents_id);

ALTER TABLE db_item
    ADD CONSTRAINT ukd6mo1s3htyeymw0h74w6a1577 UNIQUE (path);

ALTER TABLE db_item
    ADD CONSTRAINT ukg4qpx8uv9p4sofg42iqvksr0j UNIQUE (directory_id, name);

ALTER TABLE db_item
    ADD CONSTRAINT ukp5qvs9epny345ml41p58iqntt UNIQUE (blob_id);

ALTER TABLE import_registry
    ADD CONSTRAINT ukq0r5jl4clm2ook40gicn3rvbc UNIQUE (path);

ALTER TABLE usenet_download_debrid_files
    ADD CONSTRAINT ukq3h9shmsf6dvq6cxddlju0m97 UNIQUE (debrid_files_id);

ALTER TABLE torrent
    ADD CONSTRAINT fk2cxtkedgdswr97tsp8w3cohgd FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE NO ACTION;

ALTER TABLE torrent_files
    ADD CONSTRAINT fk3n0o2f40javvnmcdpbcaj5let FOREIGN KEY (torrent_id) REFERENCES torrent (id) ON DELETE NO ACTION;

ALTER TABLE db_item
    ADD CONSTRAINT fk6lcdj0neesfvlu151s9o3dllu FOREIGN KEY (directory_id) REFERENCES db_item (id) ON DELETE NO ACTION;

ALTER TABLE usenet_download_debrid_files
    ADD CONSTRAINT fk6oaxqnihnbqr7das1by8n0wek FOREIGN KEY (debrid_files_id) REFERENCES db_item (id) ON DELETE NO ACTION;

ALTER TABLE cached_file_params
    ADD CONSTRAINT fkap04otknpvq6j6qll3rsg14j5 FOREIGN KEY (cached_file_id) REFERENCES cached_file (id) ON DELETE NO ACTION;

ALTER TABLE usenet_download
    ADD CONSTRAINT fkgstgt0l4i2vkubbcm9ukcb245 FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE NO ACTION;

ALTER TABLE db_item
    ADD CONSTRAINT fknes65olued0uy6a6ed5bt5blv FOREIGN KEY (blob_id) REFERENCES blob (id) ON DELETE NO ACTION;

ALTER TABLE torrent_files
    ADD CONSTRAINT fkq2xxopa2igtu8l1i41684v87l FOREIGN KEY (files_id) REFERENCES db_item (id) ON DELETE NO ACTION;

ALTER TABLE usenet_download_debrid_files
    ADD CONSTRAINT fkuyn5wyve2hkloa4cw8xkp8eq FOREIGN KEY (usenet_download_id) REFERENCES usenet_download (id) ON DELETE NO ACTION;