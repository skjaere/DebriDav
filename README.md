# DebriDav #

[![build](https://github.com/skjaere/debridav/actions/workflows/build.yaml/badge.svg)](#)
[![codecov](https://codecov.io/gh/skjaere/debridav/graph/badge.svg?token=LIE8M1XE4H)](https://codecov.io/gh/skjaere/debridav)

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?logo=springboot&logoColor=fff)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)](#)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=fff)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## What is it?

A small app written in Kotlin that emulates the qBIttorrent API and creates virtual files that are mapped to remotely
cached files at debrid services.
DebriDav exposes these files via the WebDav protocol so that they can be mounted.

Essentially it lets you use streamable debrid links as local files so that they can be served with Jellyfin or Plex.

## How does it work?

It is designed to be used with the *arr ecosystem. DebriDav emulates the QBittorrent API, so you can add it as a
download client in Prowlarr.
Once a magnet is sent to DebriDav it will check if the torrent is cached in any of the available debrid providers and
create file representations for the streamable links of at debrid providers for the contents of the torrent.

## Which debrid services are supported?

Currently Real Debrid and Premiumize are supported.

### Note about Real Debrid

Due to changes in the Real Debrid API, to the authors best knowledge, the only way to check if a file is instantly
available
is to start the torrent and then check if the contained files have links available for streaming.
This means that if Real Debrid is enabled, every time a magnet is added to Debridav, the torrent will potentially be
started on Real Debrid's service. DebriDav will attempt to immediately delete the torrent if no links are available.

## How do I use it?

### Requirements

To build the project you will need a java 21 JDK.

### Running with Docker compose ( recommended )

See [QUICKSTART](example/QUICKSTART.md)

### Running the jar

Run `./gradlew bootJar` to build the jar, and then `java -jar build/libs/debridav-0.1.0-SNAPSHOT.jar` to run the app.
Alternatively `./gradlew bootRun` can be used.

### Running with docker

`docker run ghcr.io/skjaere/debridav:v0`

### Build docker image

To build the docker image run `./gradlew jibDockerBuild`

You will want to use rclone to mount DebriDav to a directory which can be shared among docker containers.
[docker-compose.yaml](example/docker-compose.yaml) in examples/ can be used as a starting point.

## Configuration

The following values can be defined as environment variables.

| NAME                                           | Explanation                                                                                                                                                                | Default          |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_FILE-PATH                             | The file path to store the virtual files locally                                                                                                                           | ./debridav-files |
| DEBRIDAV_DOWNLOAD-PATH                         | The path under $DEBRIDAV_FILE-PATH where downloaded files will be placed.                                                                                                  | /downloads       |
| DEBRIDAV_MOUNT_PATH                            | The path to where DebriDav is mounted inside docker containers.                                                                                                            | /data            |
| DEBRIDAV_CACHE-LOCAL-DEBRID-FILES-THRESHOLD-MB | The size threshold in megabytes for creating virtual files. Files smaller than this value will be downloaded to the local filesystem rather than delegated to a stream     | 2                |
| DEBRIDAV_DEBRID-CLIENTS                        | A comma separated list of enabled debrid providers. Allowed values are `REAL_DEBRID` and `PREMIUMIZE`. Note that the order determines the priority in which they are used. |                  |
| PREMIUMIZE_API-KEY                             | The api key for Premiumize                                                                                                                                                 |                  |
| REAL-DEBRID_API-KEY                            | The api key for Real Debrid                                                                                                                                                |                  |

## Developing

A docker compose file is provided in the dev directory, with Prowlarr and rclone defined. You can add a QBittorrent
download client in prowlarr and point it to the ip obtained by running `ip addr show docker0` in order to reach your
locally running DebriDav server.

