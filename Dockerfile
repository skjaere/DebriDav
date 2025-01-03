FROM openjdk:21-jdk-slim

WORKDIR /app

RUN apt-get update
RUN apt install ffmpeg -y
RUN apt install curl -y

