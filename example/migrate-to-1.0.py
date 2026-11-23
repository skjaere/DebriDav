#!/usr/bin/env python3
"""
Migrate a pre-1.0 debridav deployment to the 1.0 named-volume layout.

Pre-1.0 used bind mounts for Postgres (./pgdata) and the debridav root
directory (the path bound to DEBRIDAV_ROOTPATH, e.g. ./debridav-data).
1.0 switched to docker-managed named volumes.

This script copies data from those bind-mounted directories into the new
named volumes. The original directories are NOT deleted, so you can roll
back by re-running the pre-1.0 docker-compose.yaml against them.

Usage (from the directory that contains the new docker-compose.yml):
    python3 migrate-to-1.0.py --old-compose-file ../my-old-stack/docker-compose.yaml
    python3 migrate-to-1.0.py   # if the old stack is already down
    python3 migrate-to-1.0.py --old-pgdata ../my-old-stack/pgdata --old-data ../my-old-stack/debridav-data
    python3 migrate-to-1.0.py --arrs       # also migrate sonarr/radarr/prowlarr configs
    python3 migrate-to-1.0.py --yes        # non-interactive

See MIGRATION.md for the full upgrade procedure (env var changes, WebDAV
auth, jellyfin removal, rollback notes).
"""

import argparse
import shutil
import subprocess
import sys
import time
from pathlib import Path


PGDATA_VOLUME = "debridav-pgdata"
DATA_VOLUME = "debridav-data"
SONARR_VOLUME = "sonarr-config"
RADARR_VOLUME = "radarr-config"
PROWLARR_VOLUME = "prowlarr-config"
POSTGRES_CONTAINER = "debridav-postgres"
DEBRIDAV_CONTAINER = "debridav"


def compose_cmd():
    """Return the compose command, preferring `docker compose` over `docker-compose`."""
    if shutil.which("docker"):
        probe = subprocess.run(
            ["docker", "compose", "version"],
            capture_output=True, text=True,
        )
        if probe.returncode == 0:
            return ["docker", "compose"]
    if shutil.which("docker-compose"):
        return ["docker-compose"]
    sys.exit("Neither `docker compose` nor `docker-compose` is available on PATH.")


def run(cmd, *, check=True, capture=False):
    print(f"$ {' '.join(cmd)}")
    return subprocess.run(cmd, check=check, capture_output=capture, text=True)


def docker_mount_path(p: Path) -> str:
    """Format an absolute host path for `docker run -v`. Forward-slashes everywhere
    so Docker Desktop on Windows accepts it."""
    s = str(p.resolve())
    return s.replace("\\", "/") if sys.platform == "win32" else s


def volume_is_empty(name: str) -> bool:
    res = subprocess.run(
        ["docker", "run", "--rm",
         "-v", f"{name}:/v", "alpine", "sh", "-c", "ls -A /v"],
        capture_output=True, text=True,
    )
    return res.returncode == 0 and not res.stdout.strip()


def copy_into_volume(src: Path, volume: str, label: str) -> bool:
    if not src.exists():
        print(f"[skip] {label}: source {src} not found")
        return False
    if not src.is_dir():
        sys.exit(f"{label}: expected a directory at {src}")

    print(f"\n=== Copying {label}: {src} -> volume {volume} ===")
    run(["docker", "volume", "create", volume])
    if not volume_is_empty(volume):
        sys.exit(
            f"Volume {volume} already contains data — refusing to overwrite.\n"
            f"If you want to start over, run `docker volume rm {volume}` and re-run this script."
        )
    run([
        "docker", "run", "--rm",
        "-v", f"{docker_mount_path(src)}:/src:ro",
        "-v", f"{volume}:/dst",
        "alpine", "sh", "-c", "cp -a /src/. /dst/",
    ])
    return True


def containers_using(paths):
    """Running containers with a writable bind mount that overlaps any of `paths`
    (the path itself, a directory inside it, or a directory containing it), as
    {container name: [mount sources]}. Read-only mounts are ignored: they can't
    change the data mid-copy, and monitoring containers mount / read-only.

    Compares host paths, so on Docker Desktop (where mount sources are paths
    inside its VM) this finds nothing; the postmaster.pid check still applies."""
    ids = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True, check=True).stdout.split()
    if not ids:
        return {}
    inspected = subprocess.run(
        ["docker", "inspect", "-f",
         "{{.Name}}{{range .Mounts}}|{{.Type}}:{{.RW}}:{{.Source}}{{end}}", *ids],
        capture_output=True, text=True, check=True,
    ).stdout
    targets = [p.resolve() for p in paths if p.exists()]
    users = {}
    for line in inspected.splitlines():
        name, *mounts = line.split("|")
        for mount in mounts:
            mount_type, rw, source = mount.split(":", 2)
            if mount_type != "bind" or rw != "true" or not source:
                continue
            source_path = Path(source)
            if any(t == source_path or t.is_relative_to(source_path) or source_path.is_relative_to(t)
                   for t in targets):
                users.setdefault(name.lstrip("/"), []).append(source)
    return users


def postmaster_pid_present(pgdata: Path) -> bool:
    """True when the pre-1.0 Postgres data directory has a postmaster.pid: the
    server is running, or didn't shut down cleanly. Checked from a container
    because the directory is mode 0700 and owned by the postgres uid (999).
    Pre-1.0 kept the cluster in a pgdata/ subdirectory of ./pgdata."""
    res = subprocess.run(
        ["docker", "run", "--rm", "-v", f"{docker_mount_path(pgdata)}:/src:ro", "alpine",
         "sh", "-c", "test -e /src/postmaster.pid || test -e /src/pgdata/postmaster.pid"],
        capture_output=True, text=True,
    )
    return res.returncode == 0


def foreign_container_project(name: str, project: str):
    """The compose project of an existing container called `name` when it isn't
    `project` ("" for a container started outside compose), else None. Pre-1.0
    and 1.0 both name the app container `debridav`, so a leftover old one makes
    the 1.0 `up -d` fail after the copy."""
    res = subprocess.run(
        ["docker", "inspect", "-f", '{{index .Config.Labels "com.docker.compose.project"}}', name],
        capture_output=True, text=True,
    )
    if res.returncode != 0:
        return None
    owner = res.stdout.strip()
    return None if owner == project else owner


def check_old_stack_is_down(sources, pgdata: Path, project: str, ignore_postmaster_pid: bool):
    """Exit before copying anything if the pre-1.0 stack still uses the data."""
    users = containers_using(sources)
    if users:
        lines = "\n".join(f"  {name}: {', '.join(mounts)}" for name, mounts in sorted(users.items()))
        sys.exit(
            "These running containers still use the data to migrate:\n"
            f"{lines}\n"
            "Copying a running Postgres (or a live Sonarr/Radarr database) produces a corrupt copy.\n"
            "Stop the old stack first: pass --old-compose-file with its compose file, or run\n"
            "`docker compose -f <old compose file> down` (or `docker stop` the containers above)."
        )
    if pgdata.exists() and postmaster_pid_present(pgdata) and not ignore_postmaster_pid:
        sys.exit(
            f"{pgdata} has a postmaster.pid: the old Postgres is still running, or it didn't shut down\n"
            "cleanly. Start the old stack and stop it with `docker compose down`, then re-run this\n"
            "script. If you're sure no Postgres uses the directory, pass --ignore-postmaster-pid."
        )
    owner = foreign_container_project(DEBRIDAV_CONTAINER, project)
    if owner is not None:
        sys.exit(
            f"A container named `{DEBRIDAV_CONTAINER}` from "
            f"{'compose project ' + repr(owner) if owner else 'outside compose'} still exists, and the 1.0 stack\n"
            f"needs that name. Remove it with `docker compose -f <old compose file> down`, or\n"
            f"`docker rm {DEBRIDAV_CONTAINER}` if it's stopped, then re-run this script."
        )


def wait_for_postgres_healthy(timeout_s: int = 180):
    print("\n=== Waiting for postgres healthcheck ===")
    deadline = time.time() + timeout_s
    last_status = ""
    while time.time() < deadline:
        res = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Health.Status}}", POSTGRES_CONTAINER],
            capture_output=True, text=True,
        )
        status = res.stdout.strip()
        if status == "healthy":
            print("postgres is healthy.")
            return True
        if status and status != last_status:
            print(f"  postgres: {status}")
            last_status = status
        time.sleep(3)
    print(f"Timed out after {timeout_s}s. Check `docker logs {POSTGRES_CONTAINER}`.")
    return False


def main():
    parser = argparse.ArgumentParser(
        description="Migrate pre-1.0 debridav data into 1.0 named volumes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--compose-file", type=Path, default=Path("docker-compose.yml"))
    parser.add_argument("--old-compose-file", type=Path, default=None,
                        help="The pre-1.0 stack's compose file; the script runs `down` on it before copying.")
    parser.add_argument("--old-pgdata", type=Path, default=Path("./pgdata"),
                        help="Pre-1.0 Postgres data directory (default: ./pgdata)")
    parser.add_argument("--old-data", type=Path, default=Path("./debridav-data"),
                        help="Pre-1.0 debridav root directory (default: ./debridav-data)")
    parser.add_argument("--arrs", action="store_true",
                        help="Also migrate sonarr/radarr/prowlarr config directories.")
    parser.add_argument("--old-sonarr-config", type=Path, default=Path("./sonarr-config"),
                        help="Pre-1.0 Sonarr config dir (default: ./sonarr-config; only used with --arrs)")
    parser.add_argument("--old-radarr-config", type=Path, default=Path("./radarr-config"),
                        help="Pre-1.0 Radarr config dir (default: ./radarr-config; only used with --arrs)")
    parser.add_argument("--old-prowlarr-config", type=Path, default=Path("./prowlarr-config"),
                        help="Pre-1.0 Prowlarr config dir (default: ./prowlarr-config; only used with --arrs)")
    parser.add_argument("--project", default=None,
                        help="Compose project name. Defaults to the compose file's parent directory name.")
    parser.add_argument("--ignore-postmaster-pid", action="store_true",
                        help="Copy even though the old Postgres data has a postmaster.pid.")
    parser.add_argument("-y", "--yes", action="store_true", help="Skip the confirmation prompt.")
    args = parser.parse_args()

    if not args.compose_file.exists():
        sys.exit(f"Compose file not found: {args.compose_file}")
    if args.old_compose_file is not None and not args.old_compose_file.exists():
        sys.exit(f"Old compose file not found: {args.old_compose_file}")

    compose = compose_cmd()
    project = args.project or args.compose_file.resolve().parent.name.lower()
    pg_full = f"{project}_{PGDATA_VOLUME}"
    data_full = f"{project}_{DATA_VOLUME}"
    sonarr_full = f"{project}_{SONARR_VOLUME}"
    radarr_full = f"{project}_{RADARR_VOLUME}"
    prowlarr_full = f"{project}_{PROWLARR_VOLUME}"

    print("debridav 1.0 data migration")
    print(f"  Compose file:  {args.compose_file.resolve()}")
    if args.old_compose_file is not None:
        print(f"  Old stack:     {args.old_compose_file.resolve()} (will be stopped)")
    print(f"  Project name:  {project}")
    print(f"  Postgres:      {args.old_pgdata}  ->  volume {pg_full}")
    print(f"  debridav data: {args.old_data}    ->  volume {data_full}")
    if args.arrs:
        print(f"  Sonarr config:   {args.old_sonarr_config}    ->  volume {sonarr_full}")
        print(f"  Radarr config:   {args.old_radarr_config}    ->  volume {radarr_full}")
        print(f"  Prowlarr config: {args.old_prowlarr_config}  ->  volume {prowlarr_full}")
    print()
    print("The old bind-mount directories will be left untouched so you can")
    print("roll back by re-running the pre-1.0 docker-compose.yaml.")

    if not args.yes:
        if input("\nContinue? [y/N] ").strip().lower() not in ("y", "yes"):
            sys.exit("Aborted.")

    if args.old_compose_file is not None:
        print("\n=== Stopping the pre-1.0 stack ===")
        run(compose + ["-f", str(args.old_compose_file), "down"])

    print("\n=== Stopping the 1.0 stack, if it is running ===")
    subprocess.run(compose + ["-f", str(args.compose_file), "down"], check=False)

    sources = [args.old_pgdata, args.old_data]
    if args.arrs:
        sources += [args.old_sonarr_config, args.old_radarr_config, args.old_prowlarr_config]
    check_old_stack_is_down(sources, args.old_pgdata, project, args.ignore_postmaster_pid)

    copied_any = False
    copied_any |= copy_into_volume(args.old_pgdata, pg_full, "Postgres data")
    copied_any |= copy_into_volume(args.old_data, data_full, "debridav data")
    if args.arrs:
        copied_any |= copy_into_volume(args.old_sonarr_config, sonarr_full, "Sonarr config")
        copied_any |= copy_into_volume(args.old_radarr_config, radarr_full, "Radarr config")
        copied_any |= copy_into_volume(args.old_prowlarr_config, prowlarr_full, "Prowlarr config")

    if not copied_any:
        sys.exit("No source data found to migrate. Nothing to do.")

    print("\n=== Starting the 1.0 stack ===")
    run(compose + ["-f", str(args.compose_file), "up", "-d"])

    pg_ok = wait_for_postgres_healthy()
    if not pg_ok:
        print("\nPostgres did not become healthy. The stack is still running so")
        print("you can investigate. Inspect logs with:")
        print(f"  docker logs {POSTGRES_CONTAINER}")
        sys.exit(1)

    print()
    print("Postgres is up on the migrated volume. Flyway will now run any")
    print("pending migrations as debridav starts. There are several new")
    print("migrations in 1.0 — watch the logs to confirm they all succeed:")
    print()
    print(f"  {' '.join(compose)} -f {args.compose_file} logs -f {DEBRIDAV_CONTAINER}")
    print()
    print("If migrations succeed and the app comes up cleanly, you can")
    print(f"delete the old bind-mount directories ({args.old_pgdata}, {args.old_data})")
    print("at your leisure.")
    print()
    rollback_volumes = [pg_full, data_full]
    if args.arrs:
        rollback_volumes += [sonarr_full, radarr_full, prowlarr_full]
    print("To roll back:")
    print(f"  {' '.join(compose)} -f {args.compose_file} down")
    print(f"  docker volume rm {' '.join(rollback_volumes)}")
    print("  # then re-run your pre-1.0 docker-compose.yaml against the original directories")


if __name__ == "__main__":
    main()
