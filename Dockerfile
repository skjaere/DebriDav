FROM openjdk:21-jdk-slim

WORKDIR /app

RUN apt-get update

RUN mkdir app
COPY build/libs/debridav-0.1.0.jar app/app.jar
EXPOSE 8080

CMD ["java", "-jar", "app/app.jar"]
