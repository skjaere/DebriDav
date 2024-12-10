# Quickstart for docker compose

This guide will help you get up and running with DebriDav and the *arr ecosystem.

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
- Save when done.

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

All downloads will initially appear in debridav/downloads. Downloads added by Sonarr and Radarr will get moved to their
respective locations configured further down, while downloads added by Prowlarr stay in debridav/downloads.

If the requested magnet is not available in any configured debrid services, adding the magnet will fail indicating
that the torrent is not cached.

## Configure Sonarr/Radarr

The steps for both of these services are exactly the same so they must be repeated for each of them.
Navigate to http://localhost:7878 and http://localhost:8989. Once again you will be asked to configure authentication.

## Configure library

From the directory containing `docker-compose.yaml`, create two new directories:

- ./debridav/tv
- ./debridav/movies

Then, in both Sonarr and Radarr, navigate to Settings-> Media Management add click "Add Root Folder"

- For Radarr, select /data/movies
- For Sonarr, select /data/tv

## Add download client

Once done follow the same steps as for Prowlarr to add the download client in both Sonarr and Radarr.

## Set up Prowlarr integrations

In order for Radarr and Sonarr to be able to search for content, we need to set up the Prowlarr integration so that the
indexers we configured in Prowlarr can be used by Sonarr and Radarr.
Navigate to http://localhost:9696 and click on Settings -> Apps, and click the '+' card to add Sonarr and Radarr.

For Sonarr:

- Set Prowlarr Server to http://prowlarr-debridav:9696
- Set Sonarr Server to http://sonarr-debridav:8990
- Set API Key to the key obtained from http://localhost:8990/settings/general
- Click the test button to test the configuration, and save it if valid.

For Radarr:

- Set Prowlarr Server to http://prowlarr-debridav:9696
- Set Radarr Server to http://radarr-debridav:7878
- Set API Key to the key obtained from http://localhost:7878/settings/general
- Click the test button to test the configuration, and save it if valid.

Once done, you should see the indexers you created in Prowlarr under Settings -> Indexers in both Sonarr and Radarr

## Jellyfin

Navigate to http://localhost:8096 and follow the set up wizard. The content will appear under /data. I recommend that
you add /data/tv as a tv library and /data/movies as a movie library.
As of right now, automatic adding of new files to libraries in Jellyfin is not working, so you may need to trigger
a scan manually if you've added new content. This may be fixed in a future release.

And that's it! You should now be able to search for and download content with Prowlarr, Radarr and Sonarr.
Your content will be visible in the /debridav directory.

