# DebriDAV — Docker Compose example

Three stacks in one directory, stackable via Compose overrides:

- **Minimal** (`docker-compose.yml`) — debridav backend + Postgres + rclone mount. Enough to serve the WebDAV, run the UI, and have your media server read from a mounted directory.
- **Arrs** (`docker-compose.arrs.yml`) — adds Sonarr, Radarr, and Prowlarr co-located on the same network, with `/data` pointed at the same rclone mount.
- **Monitoring** (`docker-compose.monitoring.yml`) — adds Prometheus, Grafana (with pre-provisioned debridav dashboards), Postgres exporter, and cAdvisor.

Arrs and monitoring files are *overrides*: you run them alongside the base file, not instead of it. They stack — combine any or all.

## Quick start

```bash
cp .env.example .env
# Edit .env: set POSTGRES_PASSWORD, DEBRIDAV_WEBDAV_USERNAME/PASSWORD,
# and RCLONE_MOUNT_PATH. Everything else can be configured from the UI.

docker compose up -d
```

Wait ~30s for the backend to migrate the database, then:

- **UI** → http://localhost:8080/ (bundled with the backend JAR)
- **WebDAV** → http://localhost:8080/webdav/ (basic auth: `DEBRIDAV_WEBDAV_USERNAME` / `..._PASSWORD`)
- **Mounted filesystem** → the path you set as `RCLONE_MOUNT_PATH` on the host

Open the UI, head to the **Configuration** pages, and enable the debrid providers you use (add API keys, add an NNTP pool if you use Usenet, wire up Sonarr/Radarr). The settings persist in the database — no restart needed.

Point Jellyfin/Plex at `RCLONE_MOUNT_PATH` for media. Point your *arrs at the debridav backend (qBittorrent-compatible API on `:8080`, SABnzbd-compatible API on `:8080/api`).

## With *arrs (Sonarr / Radarr / Prowlarr)

```bash
docker compose -f docker-compose.yml -f docker-compose.arrs.yml up -d
```

- **Sonarr** → http://localhost:8989
- **Radarr** → http://localhost:7878
- **Prowlarr** → http://localhost:9696

All three read their media from the same rclone mount as debridav (`/home/debridav/data` inside the containers). Configs live in named Docker volumes (`sonarr-config`, `radarr-config`, `prowlarr-config`).

On first run, inside each *arr UI:

- Add debridav as the download client — qBittorrent host `http://debridav:8080`, SABnzbd host `http://debridav:8080/api` for Usenet.
- Set the root/library folder to `/home/debridav/data/tv` (Sonarr) or `/home/debridav/data/movies` (Radarr). debridav creates these on demand.
- In Prowlarr, connect to Sonarr/Radarr via `http://sonarr:8989` / `http://radarr:7878`.

If you also want debridav to push cleanup actions back to the *arrs (blocklist + research on failed downloads), set `SONARR_INTEGRATION_ENABLED=true` and `SONARR_API_KEY=…` in `.env` (same for Radarr).

## With monitoring

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d
```

- **Grafana** → http://localhost:3000 (default admin/admin; override in `.env`)
- **Prometheus** → http://localhost:9090

The monitoring stack also includes **scraparr**, a Prometheus exporter for Sonarr/Radarr. It's only useful when the arrs override is also running and `SONARR_API_KEY` / `RADARR_API_KEY` are set in `.env` — otherwise the Sonarr & Radarr dashboard renders empty.

## Everything together

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.arrs.yml \
  -f docker-compose.monitoring.yml \
  up -d
```

A tip: alias long invocations. E.g. in your shell:

```bash
alias dc-full="docker compose -f docker-compose.yml -f docker-compose.arrs.yml -f docker-compose.monitoring.yml"
dc-full up -d
dc-full logs -f debridav
```

## Host FUSE prerequisites

The `rclone` container mounts the WebDAV filesystem via FUSE. Two host-side requirements:

- `/dev/fuse` accessible (default on most distros).
- `user_allow_other` in `/etc/fuse.conf` — required because the mount uses `--allow-other` so other containers (Jellyfin/Plex, the *arrs) can read it:
  ```bash
  grep -q '^user_allow_other' /etc/fuse.conf || echo 'user_allow_other' | sudo tee -a /etc/fuse.conf
  ```

**Ubuntu 23.10+ note.** The default AppArmor profile for `fusermount3` only allows FUSE mounts under user home directories. `RCLONE_MOUNT_PATH` therefore defaults to `$HOME/debridav`. If you need the mount elsewhere (e.g. `/srv/debridav` or a separate disk), loosen the profile on the host:

```bash
sudo ln -s /etc/apparmor.d/fusermount3 /etc/apparmor.d/disable/
sudo apparmor_parser -R /etc/apparmor.d/fusermount3
```

Or edit `/etc/apparmor.d/fusermount3` and change the `-> @{HOME}/**/` rule to `-> /**/`, then `sudo apparmor_parser -r /etc/apparmor.d/fusermount3`.

## Rclone cache invalidation (optional)

Rclone's directory cache can lag behind real filesystem state — newly imported files won't show up to the *arrs until the cache expires (see `--dir-cache-time` in the rclone command). To close this gap, debridav can call rclone's RC `/vfs/refresh` endpoint whenever a file is created, moved, or deleted.

The compose stack wires this up by default but leaves it **off** in debridav:

1. Rclone is already started with `--rc --rc-addr :5572 --rc-user/--rc-pass` (auth from `RCLONE_RC_USER` / `RCLONE_RC_PASSWORD` in `.env`).
2. Debridav already receives the URL and credentials via `DEBRIDAV_RCLONE_RC-URL` / `_RC-USER` / `_RC-PASSWORD`.
3. To activate: open the UI's **Configuration → Core** page, flip **Rclone Cache Invalidation** to on, save. No restart needed.

Events are coalesced over a 500 ms window — bursts (e.g. an NZB import touching many files) produce one HTTP call per affected directory, not N calls per file.

## Security scope

This compose stack assumes you're running it on a private network. debridav's
built-in JWT auth protects the UI and its API-key endpoints, but everything
else — Grafana (anonymous Viewer), Prometheus, cAdvisor, rclone's RC port,
and the *arrs' web UIs — is published with its defaults.

If you plan to expose any of this to the internet, put the whole stack behind
your own reverse proxy (Traefik, Caddy) and IdP (Authelia, Authentik, etc.).
TLS, forward-auth, rate limiting, and fine-grained access control are out of
scope for this example.

## Config at boot vs. in the UI

Only these need to be set in `.env` — they're the bootstrap essentials, required before the UI comes up:

| Variable | What it is |
|---|---|
| `POSTGRES_PASSWORD` | Picked once, kept stable. Stored in the `debridav-pgdata` volume. |
| `DEBRIDAV_WEBDAV_USERNAME` / `_PASSWORD` | Basic auth for the WebDAV endpoint. rclone and anyone mounting the filesystem needs these. |
| `RCLONE_MOUNT_PATH` | Host path where rclone mounts the WebDAV filesystem. Must exist and be writable by `PUID:PGID`. |

Everything else — which debrid providers are enabled, provider API keys, NNTP pools, *arr integration, cache sizes, retry timings — is editable from the UI's **Configuration** pages at runtime. Changes persist in the database and take effect without a restart.

If you want to pre-seed any of that (e.g. to not have to click through the UI on a fresh deploy) the `.env.example` file has optional variables for all of them.

## Volumes

- `debridav-data` — backend's metadata filesystem (lightweight)
- `debridav-pgdata` — Postgres data dir (the important one; back this up)
- `prometheus-data`, `grafana-data` — only exist with the monitoring override

All are named Docker volumes; inspect with `docker volume ls | grep debridav`. To wipe everything: `docker compose down -v`.

## Updating

```bash
docker compose pull debridav
docker compose up -d debridav
```

Flyway migrations run on every backend startup.

## Mounting from outside the compose stack

If you want to run rclone on the host OS (not in a container) — e.g. to mount debridav on a NAS or under systemd — here's an equivalent `rclone.conf` entry:

```ini
[debridav]
type = webdav
url = http://<docker-host>:8080/webdav/
vendor = other
user = <DEBRIDAV_WEBDAV_USERNAME>
pass = <output of: rclone obscure YOUR_PASSWORD>
```
