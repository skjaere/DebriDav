# Quickstart for docker compose

This guide will help you get up and running with DebriDav and the *arr ecosystem.

> [!WARNING]
> This guide is intended as a reference for how to set up DebriDav in a home environment, and is not suitable for deployment to a remote server.
> If you intend to deploy it on a remote server you should be comfortable with configuring firewalls and/or authentication proxies to prevent public access to DebriDav or any of the other services.

## Requirements

Docker, docker compose, and a basic understanding of how the *arr ecosystem works.

## Configuring

Open the .env file for editing.
Typically you need to change two values:

- Set `DEBRIDAV_DEBRID-CLIENTS` to a comma separated list of debrid providers you would like to use. eg.
  `premiumize,real_debrid`, or `premiumize`. If you add multiple providers they will be preferred in the order
  specified. if `premiumize,real_debrid` is used, Real Debrid will only be used for torrents not cached at Premiumize.
- If using Premiumize, set the `PREMIUMIZE_API-KEY` property to your Premiumize api key, obtained by clicking the "Show
  API Key" button at `https://www.premiumize.me/account`
- If using Real Debrid, set the `REAL-DEBRID_API-KEY` property to your real debrid API key, obtained at
  `https://real-debrid.com/apitoken`
- If using EasyNews set `EASYNEWS_USERNAME` and `EASYNEWS_PASSWORD` to your EasyNews username and password respectively.
- Save when done.

### Addtional configuration options

In addition the the configuration options described in [README](../README.md#configuration), the following configuration
variables may be set for
docker compose:

| NAME                           | Explanation                                                                                                                                                    | Default            |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|
| DEBRIDAV_MOUNT_PATH_LOCAL_FS   | The path where DebriDav will be mounted on your host filesystem.                                                                                               | ./debridav         |
| DEBRIDAV_MOUNT_PATH_CONTAINERS | The path where DebriDav will be mounted inside the docker containers. If kept at it's default values, downloads will be visible to the arrs in /data/downloads | /data              |
| DEBRIDAV_ROOT_HOST_FS          | The path on the host filesystem DebriDav will use for storage                                                                                                  | ./debridav-storage |

## Start the services

Run `docker compose up --detach`, and verify that all services started successfully by running `docker container ls`.
If the DebriDav container failed to start examine the logs by running `docker logs <container-id>`, where container id
is obtained from the output of `docker container ls`
Depending on your environment you may need to open the following ports in your firewall:

- Radarr: 7878
- Sonarr: 8989
- Prowlarr: 9696
- JellyFin: 8096
- DebriDav: 8888

Once up and running, you will see some new directories appear. Each arr-service should have it's own directory where
configuration and databases are stored, and additionally you should see a `debridav` and a `debridav-files` directory.
The `debridav` directory is where rclone has mounted the debridav WebDav server to. You can open media files for playing
from this directory. The debridav-files directory is the internal storage of DebriDav. You should not need to do
anything there. You can change the name and location of these directories in `docker-compose.yaml` and/or `.env`.

## Configure Prowlarr

Navigate to http://localhost:9696. You should be greeted with a welcome screen and asked to configure authentication.

### Add an indexer

Once authentication is configured, navigate to the Indexers section, and use the form to add an indexer.
Hint: The more popular well-known indexers will have better cache hit rates.

### Add the download client

Next, navigate to Settings -> Download Clients, and click the plus card. Under the torrents section, select qBittorrent.
Optionally change the name, and set the host to `debridav`, and leave the port at `8080`. Remove any values from the
username and password fields, and check the configuration by clicking the "Test" button. If you see a green tick, you're
all set and can save.

Optionally add a usenet download client if you wish to use a usenet indexer for Easynews. Follow the same steps as above
to add SABnzbd as a download client. Set the host to `debridav`, and port to `8080`. SABnzbd requires that clients use
either username and password, or an API-key. DebriDav does not, so just fill in any non-null value ( eg. "a"/"a" ) in
the usernmame and password fields.

All downloads will initially appear in debridav/downloads. Downloads added by Sonarr and Radarr will get moved to their
respective locations configured further down, while downloads added by Prowlarr stay in debridav/downloads.

If the requested magnet is not available in any configured debrid services, adding the magnet will fail indicating
that the torrent is not cached.

## Configure Sonarr/Radarr

The steps for both of these services are exactly the same so they must be repeated for each of them.
Navigate to http://localhost:7878 and http://localhost:8989. Once again you will be asked to configure authentication.

## Configure library

From the directory containing `docker-compose.yaml`, create three new directories:

- ./debridav/downloads
- ./debridav/tv
- ./debridav/movies

Then, in both Sonarr and Radarr, navigate to Settings-> Media Management add click "Add Root Folder"

- For Radarr, select /data/movies
- For Sonarr, select /data/tv

> [!WARNING]
> Do not set the root folders outside of the DebriDav mount root ( /data in this case ).
> Doing so will cause Sonarr/Radarr to download the entire file.

## Add download client

Once done follow the same steps as for Prowlarr to add the download client in both Sonarr and Radarr.

## Set up Prowlarr integrations

In order for Radarr and Sonarr to be able to search for content, we need to set up the Prowlarr integration so that the
indexers we configured in Prowlarr can be used by Sonarr and Radarr.
Navigate to http://localhost:9696 and click on Settings -> Apps, and click the '+' card to add Sonarr and Radarr.

For Sonarr:

- Set Prowlarr Server to http://prowlarr-debridav:9696
- Set Sonarr Server to http://sonarr-debridav:8989
- Set API Key to the key obtained from http://localhost:8989/settings/general
- Click the test button to test the configuration, and save it if valid.

For Radarr:

- Set Prowlarr Server to http://prowlarr-debridav:9696
- Set Radarr Server to http://radarr-debridav:7878
- Set API Key to the key obtained from http://localhost:7878/settings/general
- Click the test button to test the configuration, and save it if valid.

Once done, you should see the indexers you created in Prowlarr under Settings -> Indexers in both Sonarr and Radarr

## Configure Arr integration with DebriDav

> [!IMPORTANT]
> At the time of implementing this feature I was unaware of of a setting in Radarr/Sonarr that enables them to try a
> different release when receiving an error from the download client. Enabling `Redownload Failed` and
> `Redownload Failed from Interactive Search` at `/settings/downloadclients` achieves the same result as enabling the
> integration. Thus, it is recommended to do this rather than use the integration as DebriDav will return a 422 error
> response for un-cached torrents when the integration is disabled.

DebriDav features an integration with the Arr-APIs in order to make the Arrs try a different release when a torrent is
not cached during an automatic search. The downside is that interactive searches will no longer feature instant feedback
on whether an item is cached or not. If you prefer using interactive search for manually selecting a release, it is
recommended to disable the integration. If you prefer using automatic search it is recommended to enable it.

This feature only applies to torrents, as sabNZBD supports the concept of failed downloads whereas qBittorrent does
not.

To enable the Sonarr API-integration, set `SONARR_INTEGRATION_ENABLED=true` in your `.env` file.

To enable the Radarr API-integration, set `RADARR_INTEGRATION_ENABLED=true` in your `.env` file.

### Get the API-keys

The arrs will generate API-keys on their first run, so they will need to be started before we can get their API-keys.
Navigate to `/settings/general` in Sonarr/Radarr to get the keys, and apply them to `RADARR_API_KEY` and
`SONARR_API_KEY`
in your `.env` file respectively.

Then restart the stack by running `docker compose stop && docker compose start`

## Jellyfin

Navigate to http://localhost:8096 and follow the set up wizard. The content will appear under /data. I recommend that
you add /data/tv as a tv library and /data/movies as a movie library.
As of right now, automatic adding of new files to libraries in Jellyfin is not working, so you may need to trigger
a scan manually if you've added new content. This may be fixed in a future release.

And that's it! You should now be able to search for and download content with Prowlarr, Radarr and Sonarr.
Your content will be visible in the /debridav directory.

