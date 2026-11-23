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
import json
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


def compose_project(compose, compose_files, project_override=None):
    """The compose project name and {volume key: Docker volume name}, as compose resolves
    them for `up`: its name normalization (a dotted directory loses the dot),
    COMPOSE_PROJECT_NAME, a top-level `name:`, and explicit volume names. Guessing them
    from the directory name could fill volumes the stack never mounts. Also fails early
    when .env lacks a required variable, before anything is copied."""
    cmd = list(compose)
    for f in compose_files:
        cmd += ["-f", str(f)]
    if project_override:
        cmd += ["-p", project_override]
    res = subprocess.run(cmd + ["config", "--format", "json"], capture_output=True, text=True)
    if res.returncode != 0:
        sys.exit(f"`{' '.join(cmd)} config` failed:\n{res.stderr.strip()}")
    config = json.loads(res.stdout)
    project = config["name"]
    volumes = {key: (volume or {}).get("name") or f"{project}_{key}"
               for key, volume in (config.get("volumes") or {}).items()}
    return project, volumes


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


def postgres_accepts_password(container: str = POSTGRES_CONTAINER) -> bool:
    """Whether Postgres accepts the POSTGRES_PASSWORD from .env for a network login,
    the way debridav connects. Postgres applies POSTGRES_PASSWORD only when it creates
    a database, so a migrated one keeps its old password. Connects to the container's
    own address rather than localhost: the image trusts local connections without a
    password, which would make the check pass regardless."""
    res = subprocess.run(
        ["docker", "exec", container, "sh", "-c",
         'PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$(hostname -i | cut -d" " -f1)" '
         '-U debridav -d debridav -tAc "select 1"'],
        capture_output=True, text=True,
    )
    return res.returncode == 0


PASSWORD_MISMATCH = f"""
The migrated database rejects POSTGRES_PASSWORD from .env, so debridav can't connect
to it. Postgres sets the password only when it creates a database, so the migrated one
keeps its old password: `debridav` if you used the 0.11 example compose file. Either:
  - set POSTGRES_PASSWORD in .env to the old password, or
  - give the database the new password (local connections need none):
      docker exec -it {POSTGRES_CONTAINER} psql -U debridav -d debridav \\
        -c "ALTER USER debridav PASSWORD '<POSTGRES_PASSWORD from .env>'"
then run `docker compose up -d`."""


def print_rollback(stack_cmd, volumes):
    print()
    print("To roll back:")
    print(f"  {' '.join(stack_cmd)} down")
    print(f"  docker volume rm {' '.join(volumes)}")
    print("  # then re-run your pre-1.0 docker-compose.yaml against the original directories")


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
    parser.add_argument("--old-data", type=Path, default=Path("/debridav-root"),
                        help="Pre-1.0 debridav root directory, DEBRIDAV_ROOT_HOST_FS in the old .env "
                             "(default: /debridav-root, the 0.11 example's value)")
    parser.add_argument("--arrs", action="store_true",
                        help="Also migrate sonarr/radarr/prowlarr config directories.")
    parser.add_argument("--old-sonarr-config", type=Path, default=Path("./sonarr-config"),
                        help="Pre-1.0 Sonarr config dir (default: ./sonarr-config; only used with --arrs)")
    parser.add_argument("--old-radarr-config", type=Path, default=Path("./radarr-config"),
                        help="Pre-1.0 Radarr config dir (default: ./radarr-config; only used with --arrs)")
    parser.add_argument("--old-prowlarr-config", type=Path, default=Path("./prowlarr-config"),
                        help="Pre-1.0 Prowlarr config dir (default: ./prowlarr-config; only used with --arrs)")
    parser.add_argument("--arrs-compose-file", type=Path, default=None,
                        help="The arrs compose file, for --arrs (default: docker-compose.arrs.yml next to --compose-file)")
    parser.add_argument("--project", default=None,
                        help="Compose project name. Defaults to the one compose itself uses for --compose-file.")
    parser.add_argument("--ignore-postmaster-pid", action="store_true",
                        help="Copy even though the old Postgres data has a postmaster.pid.")
    parser.add_argument("-y", "--yes", action="store_true", help="Skip the confirmation prompt.")
    args = parser.parse_args()

    if not args.compose_file.exists():
        sys.exit(f"Compose file not found: {args.compose_file}")
    if args.old_compose_file is not None and not args.old_compose_file.exists():
        sys.exit(f"Old compose file not found: {args.old_compose_file}")

    compose = compose_cmd()
    compose_files = [args.compose_file]
    if args.arrs:
        arrs_file = args.arrs_compose_file or args.compose_file.parent / "docker-compose.arrs.yml"
        if not arrs_file.exists():
            sys.exit(f"Arrs compose file not found: {arrs_file} (pass --arrs-compose-file)")
        compose_files.append(arrs_file)
    project, volumes = compose_project(compose, compose_files, args.project)
    missing = [v for v in [PGDATA_VOLUME, DATA_VOLUME] + ([SONARR_VOLUME, RADARR_VOLUME, PROWLARR_VOLUME]
                                                          if args.arrs else []) if v not in volumes]
    if missing:
        sys.exit(f"The compose files define no volume(s) {', '.join(missing)}: {', '.join(map(str, compose_files))}")
    pg_full = volumes[PGDATA_VOLUME]
    data_full = volumes[DATA_VOLUME]
    sonarr_full = volumes.get(SONARR_VOLUME)
    radarr_full = volumes.get(RADARR_VOLUME)
    prowlarr_full = volumes.get(PROWLARR_VOLUME)
    # Every command for the 1.0 stack names the project, so it uses the volumes filled here.
    new_stack = compose + ["-f", str(args.compose_file), "-p", project]

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
        # Not check=True: if this fails, the in-use check below says what is still running.
        run(compose + ["-f", str(args.old_compose_file), "down"], check=False)

    print("\n=== Stopping the 1.0 stack, if it is running ===")
    subprocess.run(new_stack + ["down"], check=False)

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

    rollback_volumes = [pg_full, data_full]
    if args.arrs:
        rollback_volumes += [sonarr_full, radarr_full, prowlarr_full]

    print("\n=== Starting the 1.0 stack ===")
    # Not check=True: `up -d` fails when debridav can't reach the database, and the
    # checks below explain why better than a traceback does.
    started = run(new_stack + ["up", "-d"], check=False)

    pg_ok = wait_for_postgres_healthy()
    if not pg_ok:
        print("\nPostgres did not become healthy. The stack is still running so")
        print("you can investigate. Inspect logs with:")
        print(f"  docker logs {POSTGRES_CONTAINER}")
        print_rollback(new_stack, rollback_volumes)
        sys.exit(1)

    if not postgres_accepts_password():
        print(PASSWORD_MISMATCH)
        print_rollback(new_stack, rollback_volumes)
        sys.exit(1)

    if started.returncode != 0:
        print("\n`docker compose up -d` failed although Postgres is up. Check the")
        print("debridav logs for the reason:")
        print(f"  {' '.join(new_stack)} logs {DEBRIDAV_CONTAINER}")
        print_rollback(new_stack, rollback_volumes)
        sys.exit(1)

    print()
    print("Postgres is up on the migrated volume. Flyway will now run any")
    print("pending migrations as debridav starts. There are several new")
    print("migrations in 1.0 — watch the logs to confirm they all succeed:")
    print()
    print(f"  {' '.join(new_stack)} logs -f {DEBRIDAV_CONTAINER}")
    print()
    print("If migrations succeed and the app comes up cleanly, you can")
    print(f"delete the old bind-mount directories ({args.old_pgdata}, {args.old_data})")
    print("at your leisure.")
    print_rollback(new_stack, rollback_volumes)


if __name__ == "__main__":
    main()
