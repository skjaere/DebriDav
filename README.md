# DebriDav

[![build](https://github.com/skjaere/DebriDav/actions/workflows/ci.yml/badge.svg)](https://github.com/skjaere/DebriDav/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/skjaere/debridav/graph/badge.svg?token=LIE8M1XE4H)](https://codecov.io/gh/skjaere/debridav)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[![Discord](https://img.shields.io/badge/Discord-%235865F2.svg?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/rivenmedia)

## What is it?

A small app written in Kotlin that emulates the qBittorrent and SABnzbd APIs and creates virtual files that are mapped
to remotely cached files at debrid services, essentially acting as a download client that creates virtual file
representations of remotely hosted files rather than downloading them. DebriDav exposes these files via the WebDav
protocol so that they can be mounted.

## Features

- ☁️ **Stream from debrid providers** — Real Debrid, Premiumize, TorBox, and Easynews, with Plex/Jellyfin.
- 📡 **Stream from usenet via NNTP** — Import NZBs and stream directly from your usenet provider with a pool of NNTP connections, no intermediate download required.
- 🔀 **Multiple providers with fallback** — Enable multiple debrid providers concurrently with defined priorities. If content is not cached in the primary provider, DebriDav falls back to the next.
- 🔗 **Arr integration** — Emulates the qBittorrent and SABnzbd APIs for seamless integration with Sonarr and Radarr.
- 📁 **Virtual file management** — Sort content as you would regular files. Create directories, rename files, and move them around — no regular expressions needed. Files are exposed via WebDAV.
- 🩺 **Health checking and repair** — Automatically detect unhealthy torrents and NZBs and trigger re-searches / blocklists via Sonarr/Radarr.
- 🖥️ **Built-in dashboard UI** — React frontend bundled into the backend, with a file browser, config editor, queue/history views, and live log tailing.
- 🔧 **Runtime configuration** — Most settings are editable at runtime via the UI / config API and persist to Postgres; no pod restart required.
- 🔐 **JWT authentication** — Opt-in auth layer protecting the API, qBittorrent/SABnzbd emulation, actuator, and temporary stream tokens.
- 📊 **Prometheus metrics + Grafana dashboards** — First-class observability for streams, health checks, PGMQ queues, and the NNTP connection pool.

## Migrating from 0.11 to 1.0

1.0 contains a few breaking changes. If you are upgrading from 0.11.x, apply these in order:

- **WebDAV moved under `/webdav/`**. Update every rclone mount, media-server, and WebDAV client URL from `http://host:8080/` to `http://host:8080/webdav/`.
- **Removed config keys** (silently ignored if left set): `DEBRIDAV_ROOTPATH`, `DEBRIDAV_ENABLEFILEIMPORTONSTARTUP`, and the legacy chunk-cache knobs (`DEBRIDAV_CHUNKCACHINGGRACEPERIOD`, `DEBRIDAV_CHUNKCACHINGSIZETHRESHOLD`, `DEBRIDAV_CACHEMAXSIZE`).
- **Database migrations apply automatically** via Flyway on first start; no manual action needed.
- **Any `config_override` rows** you created via the config API under one of the now-removed keys are orphaned and can be deleted from the UI / config API.

The NNTP / Usenet streaming and Health-check + repair pipelines are new features in 1.0 — see their respective sections below — and don't require migrating existing config.

## How does it work?

It is designed to be used with the *arr ecosystem. DebriDav emulates the qBittorrent and SABnzbd APIs, so you can add it
as download clients in the arrs.
Once a magnet/nzb is sent to DebriDav it will check if it is cached in any of the available debrid providers and
create file representations for the streamable files hosted at debrid providers.

Note that content you wish to be accessible through DebriDav must be added with the qBittorrent API.
DebriDav does have an opt-in Real-Debrid sync (`REAL-DEBRID_SYNCENABLED`, default on) that periodically
pulls your account's existing torrents + downloads so they can be re-used on restart; Premiumize cloud
storage is not browsed.

## Which debrid services are supported?

Currently Real Debrid, Premiumize, Easynews and TorBox are supported. If there is demand more may be added in the
future.

### Note about Real Debrid

Due to changes in the Real Debrid API, to the authors best knowledge, the only way to check if a file is instantly
available
is to start the torrent and then check if the contained files have links available for streaming.
This means that if Real Debrid is enabled, every time a magnet is added to Debridav, the torrent will potentially be
started on Real Debrid's service. DebriDav will attempt to immediately delete the torrent if no links are available.

### Note about Easynews

Easynews does not provide apis to use the contents of an nzb file to search for streamable content, so instead DebriDav
will attempt to use the search feature to find an approximate match for the name of the nzb or torrent.

funkypenguin of Elfhosted has created [an indexer](https://github.com/elfhosted/fakearr) that pairs well with DebriDav
and Easynews.

### Note about TorBox

TorBox's usenet features are not yet supported.

## NNTP / Usenet streaming

DebriDav can stream content directly from a usenet provider over NNTP, without requiring a debrid service.
When enabled, NZB files sent via the SABnzbd API are imported and their contents are made available as
streamable virtual files through WebDAV, just like debrid-backed content.

This feature includes:
- NZB import and metadata extraction (archive contents, file sizes, etc.)
- Direct streaming from your usenet provider — no intermediate download step
- Support for both archive-based and raw NZBs, including nested archives
- Health checking with automatic repair via Sonarr/Radarr integration

### NNTP configuration

NNTP is enabled implicitly whenever at least one pool has a host. Configure each pool by its index
(`0`, `1`, …); lower-priority pools act as fill/fallback.

| Environment variable                 | Description                                                                                     | Default             |
|--------------------------------------|-------------------------------------------------------------------------------------------------|---------------------|
| NNTP_POOLS_0_HOST                    | NNTP server hostname for pool 0 (required to enable NNTP)                                       |                     |
| NNTP_POOLS_0_PORT                    | NNTP server port                                                                                | `563`               |
| NNTP_POOLS_0_USERNAME                | NNTP server username                                                                            |                     |
| NNTP_POOLS_0_PASSWORD                | NNTP server password                                                                            |                     |
| NNTP_POOLS_0_USETLS                  | Use TLS for NNTP connections                                                                    | `true`              |
| NNTP_POOLS_0_MAXCONNECTIONS          | Maximum connections in this pool                                                                | `8`                 |
| NNTP_POOLS_0_PRIORITY                | Pool priority; pool with the lowest number is preferred                                         | `0`                 |
| NNTP_CONCURRENCY                     | Number of concurrent article downloads per stream (shared across pools)                         | `4`                 |
| NNTP_READAHEADSEGMENTS               | Number of segments to read ahead during streaming                                               | same as concurrency |

Pools can also be managed live from the UI (Configuration → NNTP → Server Pools).

## Health checking & repair

Debrid links expire; hosters drop files; NZB segments age off news servers. DebriDav periodically
re-verifies every torrent's debrid links and every NZB's article availability in the background,
then routes anything that fails into a repair pipeline: blocklist the broken release in Sonarr /
Radarr, trigger a re-search, and delete the virtual file if no replacement is found.

Each check + repair runs as a PostgreSQL-backed queue (PGMQ), so retries and dead-lettering are
durable across restarts. Results show up on the Health page of the UI (Queue / History tabs)
and the Grafana dashboard.

| Environment variable                 | Description                                                                                     | Default   |
|--------------------------------------|-------------------------------------------------------------------------------------------------|-----------|
| HEALTH-CHECK_REPAIR-ENABLED          | Enable automatic repair (blocklist + re-search via Sonarr/Radarr) of unhealthy items.           | `true`    |
| HEALTH-CHECK_NZB-INTERVAL            | How often to reverify a given NZB's segments (ISO-8601 duration).                               | `P7D`     |
| HEALTH-CHECK_NZB-POLL-RATE           | How often to scan for NZBs needing a check.                                                     | `PT5M`    |
| HEALTH-CHECK_TORRENT-INTERVAL        | How often to reverify a given torrent's debrid links.                                           | `P1D`     |
| HEALTH-CHECK_TORRENT-POLL-RATE       | How often to scan for torrents needing a check.                                                 | `PT5M`    |

Arr integration is required for repair: without a Sonarr or Radarr client wired up for the
matching category, unhealthy items are deleted from the virtual filesystem rather than
re-sourced. Leave `HEALTH-CHECK_REPAIR-ENABLED=false` if you want checks-only observability
without any automated deletion.

## Rclone VFS cache invalidation

If you mount DebriDav via rclone and set rclone's `--dir-cache-time` to something comfortably long
(say, 120s) for snappy directory listings, DebriDav can push cache invalidations directly to
rclone whenever files are created, moved, or deleted — so changes show up in the mount
immediately instead of waiting for the dir-cache to expire.

| NAME                                    | Explanation                                                                                   | Default |
|-----------------------------------------|-----------------------------------------------------------------------------------------------|---------|
| DEBRIDAV_RCLONECACHEINVALIDATIONENABLED | Enable pushing VFS cache invalidations to rclone.                                              | `false` |
| DEBRIDAV_RCLONE_RC-URL                  | Rclone remote-control endpoint (e.g. `http://rclone:5572`). Blank disables the integration.   |         |
| DEBRIDAV_RCLONE_RC-USER                 | Basic-auth user for rclone RC, if configured.                                                 |         |
| DEBRIDAV_RCLONE_RC-PASSWORD             | Basic-auth password for rclone RC, if configured.                                             |         |

Rclone needs `--rc --rc-addr :5572 --rc-user ... --rc-pass ...` (or equivalent) on its command
line for the RC server to be reachable. The docker-compose example in `example/` already wires
this up.

## Monitoring

A `docker-compose.monitoring.yml` override in [`example/`](example/) layers Prometheus, Grafana, and
supporting exporters on top of the base stack. When the Grafana base URL is configured, the Dashboard
tab of the UI embeds every Grafana dashboard under the `debridav` folder. See
[example/README.md](example/README.md) for details.

## How do I use it?

### Requirements

Since 0.8.0, DebriDav requires a postgres server.
To build the project you will need a java 21 JDK.

### Running with Docker compose ( recommended )

See [example/README.md](example/README.md).

### Running the jar

Run `./gradlew bootJar` to build the jar, and then `java -jar build/libs/debridav-0.1.0-SNAPSHOT.jar` to run the app.
Alternatively `./gradlew bootRun` can be used.

### Running with docker

`docker run ghcr.io/skjaere/debridav:v1`

### Build docker image

To build the docker image run `./gradlew jibDockerBuild`

You will want to use rclone to mount DebriDav to a directory which can be shared among docker containers.
[docker-compose.yml](example/docker-compose.yml) in `example/` can be used as a starting point.

### Accessing the UI

Once DebriDav is running, point a browser at its HTTP port (default `8080`) to reach the dashboard —
`http://<host>:8080/`. The UI covers live streams, imports, health checks, repair history, the file
browser, runtime config editor, and a log tailer.

If `DEBRIDAV_AUTH_ENABLED=true`, you'll be prompted to log in with `DEBRIDAV_WEBDAV-USERNAME` /
`DEBRIDAV_WEBDAV-PASSWORD` (the same credentials also guard the WebDAV endpoint). With auth off
(the default), the UI is unauthenticated — only expose it to trusted networks.

WebDAV itself is served under `/webdav/` (e.g. `http://<host>:8080/webdav/`), separate from the
UI. Point your rclone mount or media server at that path.

## Configuration

Most settings are also editable at runtime from the UI's Configuration page; these env vars
bootstrap the defaults on first start.

### Core

| NAME                               | Explanation                                                                                                                                                                                         | Default    |
|------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| DEBRIDAV_DOWNLOADPATH              | Path reported to Sonarr/Radarr as the "download complete" directory.                                                                                                                                | /downloads |
| DEBRIDAV_MOUNTPATH                 | Path reported to Sonarr/Radarr where the WebDAV mount is visible to them.                                                                                                                           | /data      |
| DEBRIDAV_DEBRIDCLIENTS             | Comma-separated list of enabled debrid providers. Allowed values: `real_debrid`, `premiumize`, `easynews`, `torbox`. Order determines fallback priority.                                             |            |
| DEBRIDAV_DEFAULTCATEGORIES         | Comma-separated list of qBittorrent categories to create on startup.                                                                                                                                |            |
| DEBRIDAV_LOCALENTITYMAXSIZEMB      | Maximum size in MB for locally-stored (non-debrid) files. Prevents accidentally-large BLOBs in the database. `0` = unlimited.                                                                        | 50         |

### Database

| NAME                     | Explanation                                     | Default   |
|--------------------------|-------------------------------------------------|-----------|
| DEBRIDAV_DB_HOST         | Postgres host                                   | localhost |
| DEBRIDAV_DB_PORT         | Postgres port                                   | 5432      |
| DEBRIDAV_DB_DATABASENAME | Database name                                   | debridav  |
| DEBRIDAV_DB_USERNAME     | Database username                               | debridav  |
| DEBRIDAV_DB_PASSWORD     | Database password                               | debridav  |

### Authentication

| NAME                             | Explanation                                                                                                                                                  | Default |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| DEBRIDAV_AUTH_ENABLED            | Protect the UI + API with JWT login.                                                                                                                          | `false` |
| DEBRIDAV_AUTH_JWT-SECRET         | Signing key for JWTs. Must be ≥ 32 bytes. If blank, a random key is generated per process (tokens won't survive restarts). `openssl rand -base64 48`.         |         |
| DEBRIDAV_AUTH_TOKEN-EXPIRATION-HOURS | UI session token lifetime.                                                                                                                                | 24      |
| DEBRIDAV_AUTH_PROTECT-QBITTORRENT-API | Require auth on the qBittorrent emulation endpoints (turn off for Sonarr/Radarr local-network use).                                                    | `false` |
| DEBRIDAV_AUTH_PROTECT-SABNZBD-API | Require auth on the SABnzbd emulation endpoints.                                                                                                           | `false` |
| DEBRIDAV_AUTH_PROTECT-ACTUATOR   | Require auth on `/actuator/*`.                                                                                                                               | `false` |
| DEBRIDAV_WEBDAV-USERNAME         | Basic-auth username for WebDAV clients (rclone, media servers). WebDAV auth is enabled if both username and password are set.                                |         |
| DEBRIDAV_WEBDAV-PASSWORD         | Basic-auth password for WebDAV clients.                                                                                                                      |         |

### Debrid providers

| NAME                               | Explanation                                                                                                                                                                                                         | Default    |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|
| PREMIUMIZE_APIKEY                  | Premiumize API key.                                                                                                                                                                                                 |            |
| REAL-DEBRID_APIKEY                 | Real-Debrid API key.                                                                                                                                                                                                 |            |
| REAL-DEBRID_SYNCENABLED            | Periodically pull existing torrents + downloads from RD for re-use.                                                                                                                                                  | `true`     |
| REAL-DEBRID_SYNCPOLLRATE           | RD sync poll rate ([ISO-8601 duration](https://en.wikipedia.org/wiki/ISO_8601#Durations)).                                                                                                                           | PT24H      |
| TORBOX_APIKEY                      | TorBox API key.                                                                                                                                                                                                     |            |
| EASYNEWS_USERNAME                  | Easynews username.                                                                                                                                                                                                  |            |
| EASYNEWS_PASSWORD                  | Easynews password.                                                                                                                                                                                                  |            |
| EASYNEWS_ENABLEDFORTORRENTS        | Search Easynews for releases matching torrents added via the qBittorrent API.                                                                                                                                       | `true`     |
| EASYNEWS_RATELIMITWINDOWDURATION   | Rate-limit time window.                                                                                                                                                                                             | 15s        |
| EASYNEWS_ALLOWEDREQUESTSINWINDOW   | Requests allowed per window.                                                                                                                                                                                        | 10         |
| EASYNEWS_CONNECTTIMEOUT            | Easynews connect timeout (ms).                                                                                                                                                                                      | 20000      |
| EASYNEWS_SOCKETTIMEOUT             | Easynews socket read timeout (ms).                                                                                                                                                                                  | 5000       |

### Sonarr / Radarr

| NAME                      | Explanation                                           | Default   |
|---------------------------|-------------------------------------------------------|-----------|
| SONARR_INTEGRATIONENABLED | Enable Sonarr integration (blocklist + re-search).     | `false`   |
| SONARR_HOST               | Sonarr host.                                          | localhost |
| SONARR_PORT               | Sonarr port.                                          | 8989      |
| SONARR_APIKEY             | Sonarr API key.                                       |           |
| SONARR_CATEGORY           | qBittorrent category mapped to Sonarr.                | tv-sonarr |
| RADARR_INTEGRATIONENABLED | Enable Radarr integration.                            | `false`   |
| RADARR_HOST               | Radarr host.                                          | localhost |
| RADARR_PORT               | Radarr port.                                          | 7878      |
| RADARR_APIKEY             | Radarr API key.                                       |           |
| RADARR_CATEGORY           | qBittorrent category mapped to Radarr.                | radarr    |

### UI

| NAME                          | Explanation                                                                                                           | Default |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------|---------|
| DEBRIDAV_UI_GRAFANA_BASEURL   | Base URL of a reachable Grafana. When set, the Dashboard tab embeds every dashboard under Grafana's `debridav` folder. |         |
| DEBRIDAV_UI_GRAFANA_APIKEY    | Optional Grafana API key if `/api/search` requires auth. Not needed for anonymous-viewer setups.                     |         |

## Disclaimer

DebriDav is intended for use with legally obtained content only. The authors do not support or condone piracy.
