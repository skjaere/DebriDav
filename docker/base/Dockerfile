# debridav's base image: Temurin's Java 25 runtime, plus FFmpeg, which the collection player uses to check a
# release's tracks (ffprobe) and remux it for browsers (ffmpeg). A static build of the FFmpeg version the HLS
# segmenting was validated on, pinned by digest (amd64 and arm64).
#
# The release workflow pushes it as ghcr.io/skjaere/debridav-base:java25-ffmpeg8.0.1, for both platforms, and Jib
# builds the app's image on it (build.gradle.kts).
FROM mwader/static-ffmpeg@sha256:252705ff88532fa338e7065c21792756552f8fe7c212f84bc503d3c340689594 AS ffmpeg

FROM eclipse-temurin:25-jre
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
