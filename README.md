# DebriDav #

[![build](https://github.com/skjaere/debridav/actions/workflows/build.yaml/badge.svg)](#)
[![codecov](https://codecov.io/gh/skjaere/debridav/graph/badge.svg?token=LIE8M1XE4H)](https://codecov.io/gh/skjaere/debridav)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-6DB33F?logo=springboot&logoColor=fff)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-%237F52FF.svg?logo=kotlin&logoColor=white)](#)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=fff)](#)

> [!WARNING]
> The Real-Debrid integration has a serious bug that can result in a large number of download links being generated per file. It is recommended to take a backup of the root directory, and disable Real-Debrid until this is fixed. 

## What is it?

A small app written in Kotlin that emulates the qBittorrent and SABnzbd APIs and creates virtual files that are mapped
to remotely cached files at debrid services, essentially acting as a download client that creates virtual file
representations of remotely hosted files rather than downloading them. DebriDav exposes these files via the WebDav
protocol so that they can be mounted.

## Features

- Stream content from Real Debrid, Premiumize and Easynews with Plex/Jellyfin.
- Sort your content as you would regular files. You can create directories, rename files, and move them around any way
  you like. No need to write regular expressions.
- Seamless integration into the arr-ecosystem, providing a near identical experience to downloading torrents. DebriDav
  integrates with Sonarr and Radarr using the qBittorrent API,
  so they can add content, and automatically move your files for you.
- Supports multiple debrid providers. DebriDav supports enabling both Premiumize and Real Debrid concurrently with
  defined priorities. If a torrent is not cached in the primary provider, it will fall back to the secondary.

## How does it work?

It is designed to be used with the *arr ecosystem. DebriDav emulates the qBittorrent and SABnzbd APIs, so you can add it
as download clients in the arrs.
Once a magnet/nzb is sent to DebriDav it will check if it is cached in any of the available debrid providers and
create file representations for the streamable files hosted at debrid providers.

Note that DebriDav does not read the torrents added to your Real Debrid account, or your Premiumize cloud storage.
Content you wish to be accessible through DebriDav must be added with the qBittorrent API. An feature to import
these files to DebriDav may be added in the future.

## Which debrid services are supported?

Currently Real Debrid, Premiumize and Easynews are supported. If there is demand more may be added in the future.

### Note about Real Debrid

Due to changes in the Real Debrid API, to the authors best knowledge, the only way to check if a file is instantly
available
is to start the torrent and then check if the contained files have links available for streaming.
This means that if Real Debrid is enabled, every time a magnet is added to Debridav, the torrent will potentially be
started on Real Debrid's service. DebriDav will attempt to immediately delete the torrent if no links are available.

### Note about Easynews

Easynews does not provide apis to use the contents of an nzb file to search for streamable content, so instead DebriDav
will attempt to use the search feature to find an approximate match for the name of the nzb or torrent.

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

| NAME                        | Explanation                                                                                                                                                                                                                                                                                                                                   | Default          |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------|
| DEBRIDAV_ROOTPATH           | The root path of DebriDav. DebriDav will store configuration data, databases, files under this directory. When running as docker this directory refers to the path within the docker container.                                                                                                                                               | ./debridav-files |
| DEBRIDAV_DOWNLOADPATH       | The path under `DEBRIDAV_ROOT-PATH` where downloaded files will be placed.                                                                                                                                                                                                                                                                    | /downloads       |
| DEBRIDAV_DEBRIDCLIENTS      | A comma separated list of enabled debrid providers. Allowed values are `REAL_DEBRID` and `PREMIUMIZE`. Note that the order determines the priority in which they are used.                                                                                                                                                                    |                  |
| PREMIUMIZE_APIKEY           | The api key for Premiumize                                                                                                                                                                                                                                                                                                                    |                  |
| REAL-DEBRID_APIKEY          | The api key for Real Debrid                                                                                                                                                                                                                                                                                                                   |                  |
| EASYNEWS_USERNAME           | The Easynews username                                                                                                                                                                                                                                                                                                                         |                  |
| EASYNEWS_PASSWORD           | The Easynews password                                                                                                                                                                                                                                                                                                                         |                  |
| EASYNEWS_ENABLEDFORTORRENTS | If set to true, DebriDav will search for releases in Easynews matching the torrent name of torrents added via the qBittorrent API                                                                                                                                                                                                             | true             |
| SONARR_INTEGRATIONENABLED   | Enable integration of Sonarr. If set to true, Debridav will attempt to mark the torrent download as failed in Sonarr. This is needed for automatic searches in Sonarr to work. If disabled DebriDav will return a 422 Repsonse instead, which is useful for immediate feedback on the items cached availability when using interactive search | true             |
| SONARR_HOST                 | The host of Sonarr                                                                                                                                                                                                                                                                                                                            | sonarr-debridav  |
| SONARR_PORT                 | The port of Sonarr                                                                                                                                                                                                                                                                                                                            | 8989             |
| SONARR_API_KEY              | The API key for Sonarr                                                                                                                                                                                                                                                                                                                        |                  |
| SONARR_CATEGORY             | The qBittorrent cateogy Sonarr uses                                                                                                                                                                                                                                                                                                           | tv-sonarr        |
| RADARR_INTEGRATIONENABLED   | Enable integration of Radarr. See description of SONARR_INTEGRATION_ENABLED                                                                                                                                                                                                                                                                   | true             |
| RADARR_HOST                 | The host of Radarr                                                                                                                                                                                                                                                                                                                            | radarr-debridav  |
| RADARR_PORT                 | The port of Radarr                                                                                                                                                                                                                                                                                                                            | 7878             |
| RADARR_API_KEY              | The API key for Radarr                                                                                                                                                                                                                                                                                                                        |                  |
| RADARR_CATEGORY             | The qBittorrent cateogy Radarr uses                                                                                                                                                                                                                                                                                                           | radarr           |

## Developing

A docker compose file is provided in the dev directory, with Prowlarr and rclone defined. You can add a qBittorrent
download client in prowlarr and point it to the ip obtained by running `ip addr show docker0` in order to reach your
locally running DebriDav server.

