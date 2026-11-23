# Migrating to debridav 1.0

The 1.0 compose stack moved from host bind mounts to docker-managed named
volumes. This guide walks through upgrading an existing pre-1.0 deployment
without losing data.

If you're a new user installing 1.0 fresh, skip this guide — go to the
[README](README.md).

## What changed

| Concern | Pre-1.0 | 1.0 |
|---|---|---|
| Postgres data | bind mount (`./pgdata`) | named volume (`debridav-pgdata`) |
| debridav root dir | bind mount (`./debridav-files` or similar) | named volume (`debridav-data`) |
| Sonarr/Radarr/Prowlarr configs | bind mounts (`./sonarr-config` etc.) | named volumes (`sonarr-config` etc.) |
| Jellyfin | bundled in the example | dropped from the example |
| WebDAV URL path | served at `/` | served at `/webdav/` |
| WebDAV auth | none | required (`DEBRIDAV_WEBDAV_USERNAME` / `_PASSWORD`) |
| `rclone.conf` | hand-edited, bind-mounted | generated at container startup |
| Postgres port | exposed on `5432` | not exposed by default |
| Container names | `postgres-debridav`, `sonarr-debridav`, … | `debridav-postgres`, `debridav-sonarr`, … |
| `.env` variables | ~15 required | 3 required, rest moved to the UI |

The two important pieces of state are Postgres (the entire virtual filesystem,
torrent/download history, NZB documents, message queues all live here) and
your *arr configs (indexers, settings, history). Lose Postgres and you start
from scratch.

## Before you start

1. **Back up Postgres.** Even though the migration leaves the old `./pgdata`
   directory in place as a rollback, take a real dump for peace of mind:

   ```bash
   docker exec postgres-debridav pg_dump -U debridav debridav | gzip > pgdump-$(date +%F).sql.gz
   ```

2. **Pull the new compose files** alongside (or replacing) your old ones. The
   migration script and the new `docker-compose.yml` live under `example/`.

3. **Check your old paths.** The script defaults to `./pgdata` and
   `./debridav-data` for the bind mounts. If your `.env` pointed
   `DEBRIDAV_ROOT_HOST_FS` somewhere else, you'll need to pass `--old-data`.

## Step 1 — Rewrite your `.env`

The new `.env.example` is much shorter. Start fresh from it and copy across
the values you still need:

```bash
cp .env.example .env
```

Mapping from the old variables:

| Old (`.env`) | 1.0 (`.env`) | Notes |
|---|---|---|
| `DEBRIDAV_ROOT_PATH`, `DEBRIDAV_ROOT_HOST_FS`, `DEBRIDAV_DOWNLOAD_PATH`, `DEBRIDAV_MOUNT_PATH_CONTAINERS` | *(removed)* | Hardcoded in the compose file now |
| `DEBRIDAV_MOUNT_PATH_HOST_FS` | `RCLONE_MOUNT_PATH` | Renamed; same purpose (host mount path) |
| `DEBRIDAV_DB_*` (host/port/name/user/password) | `POSTGRES_PASSWORD` only | DB hostname/user/db are now fixed to `postgres`/`debridav`/`debridav` |
| *(none — WebDAV was unauthenticated)* | `DEBRIDAV_WEBDAV_USERNAME`, `DEBRIDAV_WEBDAV_PASSWORD` | **New, required.** Anything reading the WebDAV needs these. |
| `REAL_DEBRID_API_KEY`, `PREMIUMIZE_API_KEY`, `TORBOX_API_KEY`, `EASYNEWS_*`, `SONARR_*`, `RADARR_*`, `NNTP_*` | Same names, all optional | Can be left blank and configured in the UI |
| `DEBRIDAV_DEBRID_CLIENTS` | Same; optional | Can be set in the UI |

Pick a stable `RCLONE_RC_USER` / `RCLONE_RC_PASSWORD` while you're in there —
they're new in 1.0 (rclone's RC API is used for VFS cache invalidation).

## Step 2 — Run the migration script

From the directory containing the new `docker-compose.yml` (typically
`example/`):

```bash
python3 migrate-to-1.0.py
```

What the script does:

1. Stops the current stack (`docker compose down`).
2. Creates the new named volumes.
3. Copies `./pgdata` → `<project>_debridav-pgdata` and `./debridav-data` →
   `<project>_debridav-data` using a throwaway alpine container (`cp -a`,
   preserves permissions, symlinks, hidden files).
4. Starts the 1.0 stack.
5. Waits for the Postgres healthcheck to pass.
6. Prints the command to tail debridav's logs so you can watch Flyway run
   the new migrations (V1 → V12 in 1.0; several are non-trivial).

Useful flags:

```bash
# Custom paths
python3 migrate-to-1.0.py --old-pgdata ../my-stack/pgdata --old-data ../my-stack/debridav-files

# Also migrate Sonarr/Radarr/Prowlarr config dirs (see Step 4)
python3 migrate-to-1.0.py --arrs

# Non-interactive (for scripted/CI use)
python3 migrate-to-1.0.py --yes
```

The script refuses to overwrite a volume that already has data, so re-runs
are safe. The old bind-mount directories are never deleted.

## Step 3 — Verify Flyway migrations

1.0 introduces several Postgres migrations (LTree-based paths, pgmq queues,
NZB document storage, etc.). Watch them apply:

```bash
docker compose logs -f debridav
```

You should see lines like:

```
Flyway Community Edition ... by Redgate
Database: jdbc:postgresql://postgres:5432/debridav (PostgreSQL 17.x)
Successfully validated 12 migrations ...
Current version of schema "public": <your previous version>
Migrating schema "public" to version "..."
Successfully applied N migrations to schema "public", now at version v12
```

Followed by Spring's `Started DebridavApplication`. If Flyway reports an
error mid-migration, **stop here and roll back** (see the bottom of this
doc) — partially-applied migrations are not safe to leave in place.

Smoke-test the app:

- UI → http://localhost:8080/ (your existing files should be listed)
- WebDAV → http://localhost:8080/webdav/ (basic auth with the new credentials)
- A sample stream should still play

### First health checks: start with repair off

1.0 checks every torrent with your debrid providers once a day. A torrent that
no provider has any more is **repaired**: blocklisted in Sonarr/Radarr, removed
from debridav, and searched for again. None of your existing torrents have been
checked yet, so their first checks are spread over the first 24 hours after the
upgrade, and at most `health-check.max-repairs-per-hour` repairs (10 by default)
run in any hour.

A misconfiguration (a wrong provider API key, or a `debridav.mount-path` that
doesn't match where Sonarr/Radarr see the mount) would make healthy torrents
look missing. To review the first day's results before anything is repaired:

1. Before the first `docker compose up -d`, set this in `.env`:
   ```
   HEALTH_CHECK_REPAIR_ENABLED=false
   ```
2. After a day, open **Health → Queue** in the UI. Torrents the checks found
   missing are listed with the repair action `SKIPPED`.
3. If those are really gone from your provider, turn repair on: set the
   variable back to `true` and run `docker compose up -d`, or switch on
   **Repair Enabled** under **Health → Settings**. If you can still play some
   of them, fix the configuration first; `docker compose logs debridav`
   names the provider errors and any mount-path mismatch.

## Step 4 — Migrate Sonarr / Radarr / Prowlarr (optional)

If you ran the *arrs from the old example, their config dirs (`./sonarr-config`,
`./radarr-config`, `./prowlarr-config`) need to move into named volumes too.

The easiest way is to let the script handle it:

```bash
python3 migrate-to-1.0.py --arrs
```

This adds the three *arr volumes to the same migration pass. Defaults:

- `./sonarr-config` → `<project>_sonarr-config`
- `./radarr-config` → `<project>_radarr-config`
- `./prowlarr-config` → `<project>_prowlarr-config`

Override with `--old-sonarr-config <path>` etc. if yours live elsewhere.

Then bring up the arrs override alongside the base stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.arrs.yml up -d
```

**One thing to double-check inside each *arr:** the new arrs override uses
the WebDAV mount at `/home/debridav/data` inside the containers (the old
example mounted it at whatever `DEBRIDAV_MOUNT_PATH_CONTAINERS` was). If
your existing Sonarr/Radarr root folders pointed at a different in-container
path, edit them under **Settings → Media Management → Root Folders** and
re-point them at the new path. Sonarr/Radarr will treat a renamed root as
a new library, so this matters.

Also: the download-client host in Sonarr/Radarr should now be
`http://debridav:8080` (matches the new container name), not whatever it
was before.

## Step 5 — Jellyfin / Plex

The old example bundled Jellyfin. The new arrs override does **not** include
a media server — most users already run their own, and it's not really
debridav-specific. Two options:

1. **Keep your existing media server container running unchanged.** It just
   needs to read from `RCLONE_MOUNT_PATH` on the host. If it's on the
   `debridav` docker network it'll keep working; if not, point it at the host
   bind path.

2. **Add Jellyfin back yourself** as a small override file next to the
   official `docker-compose.arrs.yml`. The 0.x example file at
   `example_old/docker-compose.yaml` is a fine starting point for the
   Jellyfin block.

If your Jellyfin's library DB lives in a bind-mounted `./jellyfin-config`,
copy it into a named volume the same way:

```bash
docker volume create jellyfin-config
docker run --rm \
  -v "$(pwd)/jellyfin-config":/src:ro \
  -v jellyfin-config:/dst \
  alpine sh -c 'cp -a /src/. /dst/'
```

## Step 6 — Monitoring (no data migration needed)

The old monitoring stack had no persistence volumes for Prometheus or
Grafana — both restarted from scratch on every container restart. The new
monitoring override adds `prometheus-data` and `grafana-data` volumes, so
your historical metrics and any dashboards you'd customized in-place will
not transfer (there's nothing on disk to transfer). The pre-provisioned
debridav dashboards reload automatically.

If you want metric history continuity, dump Prometheus' TSDB before tearing
the old stack down. Most users don't bother.

## Step 7 — Reconnect external clients

A few things to check off after the new stack is up:

- **WebDAV mounts on other hosts** (NAS, remote rclone, scripts) — two
  things changed and both need updating:
  - **URL path moved from `/` to `/webdav/`.** Old: `http://<host>:8080/`.
    New: `http://<host>:8080/webdav/`. The bundled rclone container's
    config is regenerated automatically, but external rclone instances,
    NAS WebDAV mounts, or scripts pointing at the old root URL will 404
    after the upgrade.
  - **Basic auth is now required.** Set `DEBRIDAV_WEBDAV_USERNAME` /
    `_PASSWORD` on the server, then add matching credentials to each
    external client (`user`/`pass` in `rclone.conf` — remember to
    `rclone obscure` the password — or the equivalent in your NAS UI).
    Pre-1.0 served the WebDAV unauthenticated, so these clients likely
    have no credentials configured at all today.

  Example minimal external `rclone.conf` after the upgrade:
  ```ini
  [debridav]
  type = webdav
  url = http://<docker-host>:8080/webdav/
  vendor = other
  user = <DEBRIDAV_WEBDAV_USERNAME>
  pass = <output of: rclone obscure YOUR_PASSWORD>
  ```
- **External Postgres access** — the new compose doesn't publish `5432` to
  the host. If you have external tools that talked to the DB directly, add
  the port back in a local override file:
  ```yaml
  services:
    postgres:
      ports:
        - "5432:5432"
  ```
- **Monitoring scripts** that referenced the old container names
  (`postgres-debridav`, `sonarr-debridav`, etc.) — update to the new names
  (`debridav-postgres`, `debridav-sonarr`, …).

## Rollback

The migration is non-destructive — the old bind-mount directories are
untouched, so rollback is just bringing the old stack back up against them.

```bash
# Stop the 1.0 stack
docker compose down

# Remove the volumes the script created so you can re-run it cleanly later
docker volume rm <project>_debridav-pgdata <project>_debridav-data
# If you migrated arrs:
docker volume rm <project>_sonarr-config <project>_radarr-config <project>_prowlarr-config

# Bring the old stack back up
docker compose -f /path/to/pre-1.0/docker-compose.yaml up -d
```

`<project>` is the compose project name — by default the directory name of
the compose file. Check with `docker volume ls | grep debridav` if unsure.

If Flyway partially applied migrations and then errored out, **don't try to
re-run 1.0 against the partially-migrated volume** — destroy that volume
(`docker volume rm`) and re-run the migration script from the untouched
`./pgdata` directory. That's the whole reason the original bind dirs are
preserved.

## Common issues

**Script fails with "Volume X is not empty, refusing to overwrite."**
You ran the script once already and it created the volume. Remove it
(`docker volume rm <name>`) and re-run. Source data is untouched.

**`postgres` container restart-loops on startup.**
Usually a UID/GID mismatch on the copied files. `cp -a` preserves ownership
from the source, which on Linux is `999:999` for postgres but on macOS-shared
volumes can come through as something else. Check `docker logs debridav-postgres`
for `chown` or "permission denied" errors. Fix:
```bash
docker run --rm -v <project>_debridav-pgdata:/v alpine chown -R 999:999 /v
```

**Sonarr/Radarr report "all downloads stuck importing" after migration.**
Their root folder path inside the container changed (see Step 4). Re-point
the root folder, then in Activity → Queue, click each item and "Manually
import" so Sonarr/Radarr reconcile against the new path.

**Flyway error mentioning the `pgmq` schema.**
The pgmq extension installs via migration V12. If your old Postgres image
is missing required extensions, V12 will fail. The new compose pins
`postgres:17`, which is fine — make sure the volume is mounted into the
1.0 `postgres:17` container, not the old image.
