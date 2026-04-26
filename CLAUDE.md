# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build
./gradlew build                    # Full build with tests
./gradlew bootJar                  # Build Spring Boot JAR
./gradlew bootRun                  # Run application directly
./gradlew jibDockerBuild           # Build Docker image locally

# Test
./gradlew test                     # Run all tests
./gradlew test --tests "io.skjaere.debridav.test.SomeTest"  # Run single test class
./gradlew test --tests "*SomeTest.testMethod"               # Run single test method

# Other
./gradlew compileKotlin            # Compile only (no tests)
```

## Technology Stack

- **Kotlin 2.3.0** with **Java 25** (virtual threads via Loom)
- **Spring Boot 4.0.0** with Spring Data JPA
- **PostgreSQL** with Flyway migrations
- **Ktor 3.3.3** for HTTP client operations
- **Milton 4.0.4** for WebDAV protocol
- **Kotlin Coroutines** with custom `Dispatchers.LOOM` for virtual thread integration

## Architecture Overview

DebriDAV creates a WebDAV-mountable virtual filesystem backed by debrid service providers. It emulates the qBittorrent and SABnzbd APIs for integration with Sonarr/Radarr.

### Core Modules

| Package | Purpose |
|---------|---------|
| `debrid/client/` | Provider implementations (RealDebrid, Premiumize, Easynews, TorBox) with abstract `DebridClient` |
| `fs/` | Virtual filesystem layer - `DatabaseFileService` manages file hierarchy using PostgreSQL LTree |
| `resource/` | WebDAV resource factory connecting Milton to the virtual FS |
| `torrent/` | qBittorrent API emulation (`QBittorrentEmulationController`) |
| `nntp/` | Usenet/NZB support with streaming RAR parsing and Yenc decompression |
| `archive/` | RAR file parsing (`Rar4Parser`) for metadata extraction |
| `cache/` | Byte-range caching for metadata extraction (`FileChunkCachingService`) |
| `arrs/` | Sonarr/Radarr integration services |

### Data Flow

1. Sonarr/Radarr send requests to qBittorrent-emulated API
2. Torrent/NZB content checked against debrid provider caches
3. Cached content registered in PostgreSQL as virtual files
4. WebDAV server exposes virtual filesystem for media server mounting
5. On file access, content streamed from debrid provider with chunk caching

### Database

- PostgreSQL required (uses LTree extension for hierarchical paths)
- Entities: `Torrent`, `UsenetEntity`, `DebridFileContents`, `FileChunk`
- Migrations in `src/main/resources/db/migration/` (V1-V11)

## Code Patterns

**Configuration**: Use `@ConfigurationProperties` classes in `DebridavConfiguration.kt`. Properties defined in `application.properties`.

**Async operations**: Use Kotlin coroutines with `Dispatchers.LOOM` for blocking I/O:
```kotlin
withContext(Dispatchers.LOOM) {
    // blocking operation
}
```

**Transactions**: Use `TransactionTemplate` for explicit transaction boundaries in services.

**Testing**: Integration tests use TestContainers (PostgreSQL), MockServer for HTTP, MockK for mocking. Tests in `src/test/kotlin/io/skjaere/debridav/test/`.

## Real-Debrid Integration

### Key Files

| File | Purpose |
|------|---------|
| `debrid/client/realdebrid/RealDebridClient.kt` | Main client — cache checking, torrent management, link unrestriction |
| `debrid/client/realdebrid/support/RealDebridTorrentService.kt` | Torrent sync and DB persistence |
| `debrid/client/realdebrid/support/RealDebridDownloadService.kt` | Download sync and DB persistence |
| `debrid/client/realdebrid/RealDebridConfigurationProperties.kt` | Configuration properties (`real-debrid.*`) |
| `debrid/client/realdebrid/RealDebridConfiguration.kt` | Spring bean config including Resilience4j rate limiter |
| `debrid/client/realdebrid/RealDebridActuatorEndpoint.kt` | Actuator endpoint for toggling torrent import at runtime |

### Class Hierarchy

`RealDebridClient` extends `DebridCachedTorrentClient` and `DebridCachedContentClient`, implements `StreamableLinkPreparable` (delegated to `DefaultStreamableLinkPreparer`) and `ConfigurationTester`.

### API Endpoints Used

All calls go to `https://api.real-debrid.com/rest/1.0` (configurable). Authentication is via Bearer token (`real-debrid.api-key`).

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/torrents/addMagnet` | POST | Submit magnet link (form-encoded `magnet=...`) |
| `/torrents/` | GET | List user's torrents (paginated, 100/page) |
| `/torrents/info/{id}` | GET | Get torrent info with files and links |
| `/torrents/selectFiles/{id}` | POST | Select files from torrent (form-encoded `files=1,2,3`) |
| `/torrents/delete/{id}` | DELETE | Remove torrent from account |
| `/unrestrict/link` | POST | Convert RD share link → direct download URL |
| `/downloads` | GET | List user's downloads (paginated, 100/page) |
| `/downloads/delete/{id}` | DELETE | Remove download from account |
| `/user` | GET | Validate API key (used by `ConfigurationTester`) |

### End-to-End Flow

**Phase 1 — Torrent Addition:**
1. `getCachedFiles(magnet)` checks DB for existing torrent by info hash
2. If not found: `POST /torrents/addMagnet` → `GET /torrents/info/{id}` → save to `RealDebridTorrentEntity`

**Phase 2 — File Selection:**
1. `getIdsToSelect()` filters for video files (`.mp4`, `.mkv`, `.avi`, `.ts`)
2. `POST /torrents/selectFiles/{id}` with selected file IDs
3. `GET /torrents/info/{id}` to retrieve links for selected files
4. If no links available (not cached): DELETE torrent, return empty list

**Phase 3 — Link Unrestriction:**
1. For each file link, check DB for existing `RealDebridDownloadEntity`
2. If not found: `POST /unrestrict/link` → returns direct download URL, saved to DB
3. Returns `List<CachedFile>` with path, download URL, MIME type, and params (`torrentId`, `linkId`)

**Phase 4 — Streaming:**
1. `getStreamableLink()` looks up download by hash + filename + size in DB
2. `isLinkAlive()` — HEAD request to download URL (rate-limited, cached 5 min)
3. If alive: return URL. If dead: delete and fetch fresh link via unrestrict
4. `DefaultStreamableLinkPreparer` builds Ktor HTTP GET with byte-range headers for seeking support

### Rate Limiting

Resilience4j `RateLimiter`: **249 requests per 1 minute** (just under RD's ~250/min limit), 5-second timeout per acquisition.

### Scheduled Sync

`syncTorrentsTask()` runs on a configurable schedule (`real-debrid.sync-poll-rate`, default `PT24H`):
- Clears and re-fetches all `RealDebridTorrentEntity` records (paginated `/torrents/`)
- Clears and re-fetches all `RealDebridDownloadEntity` records (paginated `/downloads`)
- Can be toggled at runtime via the actuator endpoint

### Database Entities

**`RealDebridTorrentEntity`**: `torrentId` (indexed), `name`, `hash` (indexed), `links` (ElementCollection), `files` (one-to-many `TorrentsInfoFile`)

**`RealDebridDownloadEntity`**: `downloadId` (indexed), `filename`, `mimeType`, `fileSize`, `link` (RD share link), `host`, `download` (actual URL), `chunks`, `streamable`

**Key query**: `getDownloadByHashAndFilenameAndSize()` — native SQL joining downloads → torrent links → torrents to find a download by torrent hash + filename + file size.

### Error Handling

- `isCached()` always returns `true` (RD doesn't expose a cache-check API; availability is determined during file selection)
- HTTP 4xx → `DebridClientError`, 5xx → `DebridProviderError`
- `addMagnet` failures return `FailedAddMagnetResponse` with reason (not thrown)
- `unrestrict` failures logged as warnings, return `ErrorUnrestrictLinkResponse`
- Configurable retries in `DebridCachedContentService` (default 1, 200ms delay)

## NNTP/Usenet Integration

### External Artifacts

| Artifact | Version | Purpose |
|----------|---------|---------|
| `com.github.skjaere:nzb-streamer` | 0.7.0 | NZB parsing, NNTP article fetching, Yenc decompression, RAR/7zip archive parsing, and file streaming |
| `com.github.skjaere:mock-nntp-server` | 0.2.0 | Test-only mock NNTP server |

**nzb-streamer** internally depends on **ktor-nntp-client** (a Ktor-based NNTP protocol client) for connecting to Usenet servers, fetching articles by message-ID, and managing connection pools with TLS support and server priority failover.

### Key Files

| File | Purpose |
|------|---------|
| `usenet/NzbStreamerConfiguration.kt` | NNTP server pool config, creates `NzbStreamer` bean |
| `usenet/NzbImportService.kt` | Orchestrates NZB import: parse → extract metadata → register in filesystem |
| `usenet/NzbHealthCheckService.kt` | Scheduled verification that NZB segments still exist on Usenet |
| `usenet/sabnzbd/SabnzbdApiController.kt` | SABnzbd API emulation endpoints |
| `usenet/sabnzbd/SabNzbdService.kt` | NZB handling and SABnzbd response building |
| `usenet/pgmq/PgmqSpringConfiguration.kt` | PostgreSQL message queue setup (3 queues) |
| `usenet/pgmq/PgmqConsumer.kt` | Generic message consumer loop |
| `usenet/pgmq/NzbHealthCheckHandler.kt` | Processes health check messages |
| `usenet/pgmq/NzbHealthRepairHandler.kt` | Blocklists failed NZBs in Sonarr/Radarr |
| `usenet/nzb/NzbDocumentEntity.kt` | JPA entity storing parsed NZB metadata as JSONB |
| `usenet/UsenetDownload.kt` | JPA entity tracking download status |
| `usenet/queue/NzbImportRecord.kt` | JPA entity tracking import queue status |
| `resource/NzbFileResource.kt` | WebDAV resource for streaming NZB files via Milton |

### Configuration Properties

**`nntp.*`** (all conditional on `nntp.enabled=true`):
- `enabled` — enable/disable NNTP support
- `concurrency` (default 4) — concurrent NNTP streaming threads
- `forwardThresholdBytes` (default 102400) — byte threshold for forward seeking
- `healthCheckInterval` (default 7 days) — how often to reverify NZB segments
- `healthCheckPollRate` (default 5 min) — poll rate for health check scheduling
- `pools` — list of NNTP server pools, each with: `host`, `port`, `username`, `password`, `useTls`, `maxConnections`, `priority`

**`pgmq.*`**:
- `importConcurrency` (default 2) — workers processing NZB imports
- `importVisibilityTimeout` (default 10 min) — message lock duration
- `importPollInterval` (default 2 sec) — queue poll rate
- `healthCheckConcurrency` (default 1), `healthRepairConcurrency` (default 2)

### End-to-End Flow

**Phase 1 — NZB Upload (SABnzbd API emulation):**
1. Sonarr/Radarr POST NZB file to `/api?mode=addfile`
2. `SabNzbdService` creates `UsenetDownload` (QUEUED) and `NzbImportRecord` (QUEUED)
3. Sends `NzbImportMessage` (NZB bytes as Base64) to PGMQ `nzb_import` queue
4. Returns immediately to caller

**Phase 2 — Async Import (PGMQ consumer):**
1. `PgmqConsumer` picks up message from `nzb_import` queue
2. `NzbImportService.executeImport()`:
   - Decodes NZB bytes, calls `nzbStreamer.prepare(nzbBytes)`
   - nzb-streamer parses NZB XML, fetches initial articles from NNTP servers via ktor-nntp-client
   - Yenc-decodes article bodies, parses RAR/7zip archive headers to extract file metadata
   - Returns `PrepareResult`: `Success`, `MissingArticles`, `Failure`, or `UnsupportedArchive`
3. On success: `nzbStreamer.resolveStreamableFiles(metadata)` → list of files with volume/offset info
4. Creates `NzbDocumentEntity` (files + streamableFiles stored as JSONB), `NzbContents` per file, and `RemotelyCachedEntity` entries in the virtual filesystem
5. Updates `UsenetDownload.status` → COMPLETED

**Phase 3 — File Streaming (WebDAV access):**
1. Media server accesses file via WebDAV
2. `StreamableResourceFactory` creates `NzbFileResource` from `NzbContents` entity
3. `NzbFileResource.sendContent()` calls `nzbStreamer.streamFile(nzbDocument, streamableFile, range)`
4. nzb-streamer fetches NNTP articles on-demand, Yenc-decodes, reconstructs archive data, and streams the extracted file content via a `ByteReadChannel`
5. Supports byte-range requests for seeking/scrubbing

**Phase 4 — Health Check & Repair:**
1. `NzbHealthCheckService` runs on schedule, finds NZB documents not verified within `healthCheckInterval`
2. Sends `NzbHealthCheckMessage` to PGMQ `nzb_health_check` queue
3. `NzbHealthCheckHandler` calls `nzbStreamer.verifySegments()` to check article availability
4. If articles missing: sends `NzbHealthRepairMessage` to `nzb_health_repair` queue
5. `NzbHealthRepairHandler` blocklists the download in Sonarr/Radarr and triggers a new search

### Message Queue Architecture (PGMQ)

Three PostgreSQL-backed queues (installed via Flyway migration `V12__install_pgmq.sql`):

| Queue | Message Type | Handler | Concurrency |
|-------|-------------|---------|-------------|
| `nzb_import` | `NzbImportMessage` | `NzbImportService` | 2 workers |
| `nzb_health_check` | `NzbHealthCheckMessage` | `NzbHealthCheckHandler` | 1 worker |
| `nzb_health_repair` | `NzbHealthRepairMessage` | `NzbHealthRepairHandler` | 2 workers |

### Supported Archive Types

nzb-streamer handles: `RAW`, `RAR`, `SEVEN_ZIP`, `RAR_IN_SEVEN_ZIP`, `RAR_IN_RAR`, `SEVEN_ZIP_IN_RAR`, `SEVEN_ZIP_IN_SEVEN_ZIP` (nested archives).

### Database Entities

**`NzbDocumentEntity`** (table `nzb_document`): `files` (JSONB — Yenc headers + segment article IDs), `streamableFiles` (JSONB — file paths with volume/offset positions), `archiveType`, `lastVerified`, `name`, `category`

**`UsenetDownload`**: `status` (QUEUED → DOWNLOADING → COMPLETED/FAILED/ARTICLES_MISSING), `name`, `hash` (MD5 of NZB), `size`, `category`, references `NzbDocumentEntity`

**`NzbImportRecord`** (table `nzb_import`): tracks import queue status with `status`, `archiveType`, `errorMessage`, `files` (JSONB), timestamps

**`NzbContents`** (extends `DebridFileContents`): `originalPath`, `size`, `mimeType`, references `NzbDocumentEntity`

### SABnzbd API Emulation

Endpoints at `/api` (emulating SABnzbd v4.4.0):
- `mode=addfile` — multipart NZB upload
- `mode=queue` — returns queue status
- `mode=history` — completed/failed downloads from DB
- `mode=get_config` — categories and configuration
- `mode=version` — returns "4.4.0"
- `mode=fullstatus` — static status with configured paths

## Configuration

Key properties in `application.properties`:
- `debridav.debrid-clients` - Enabled providers (real-debrid, premiumize, easynews, torbox)
- `debridav.root-path` - WebDAV root path
- `debridav.download-path` - Download directory path
- Provider-specific API keys and settings
