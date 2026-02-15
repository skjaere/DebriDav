import com.google.cloud.tools.jib.gradle.JibTask
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

group = "io.skjaere"
version = providers.gradleProperty("version").get()
description = "DebriDav"

plugins {
    `java-library`
    `maven-publish`
    jacoco
    application
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    kotlin("plugin.jpa") version "2.3.0"
    id("dev.detekt") version "2.0.0-alpha.2"
    id("org.springframework.boot") version "4.0.1"
    id("com.google.cloud.tools.jib") version "3.5.3"
    id("io.github.simonhauck.release") version "1.3.0"
}

application {
    mainClass = "io.skjaere.debridav.DebriDavApplicationKt"
}

repositories {
    mavenLocal()
    mavenCentral()

    maven {
        url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap")
    }
    maven {
        url = uri("https://jitpack.io")
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "25"
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "25"
}

detekt {
    buildUponDefaultConfig = true // preconfigure defaults
    allRules = false // activate all available (even unstable) rules.
    baseline = file("$projectDir/config/baseline.xml") // a way of suppressing issues before introducing detekt
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        csv.required = true
        html.required = true
    }
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)
    implementation(libs.milton.server.ce)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.postgresql)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.guava)
    implementation(libs.httpclient5)
    implementation(libs.httpcore5)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    implementation(libs.jul.to.slf4j)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.flyway.database.postgresql)
    implementation(libs.java.multibase)
    implementation(libs.bencode)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.prometheus.metrics.core)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.hypersistence.utils)
    implementation(libs.resilience4j.kotlin)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.retry)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.client.apache5)
    implementation(libs.ktor.client.java)
    implementation(libs.nzb.streamer)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.webtestclient)
    testImplementation(libs.mockserver.netty)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin) // TODO: remove
    testImplementation(libs.mockk.jvm)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.hamcrest)
    testImplementation(libs.sardine)
    testImplementation(libs.ktor.client.mock)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}

tasks.withType<Test>() {
    finalizedBy(tasks.jacocoTestReport)
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }

    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("passed", "failed", "skipped")
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

tasks.withType<JibTask>().configureEach {
    notCompatibleWithConfigurationCache("because https://github.com/GoogleContainerTools/jib/issues/3132")
}

jib {
    from {
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
            platform {
                architecture = "arm64"
                os = "linux"
            }
        }
    }
    to {
        image = "ghcr.io/skjaere/debridav"
        auth {
            username = "skjaere"
            password = System.getenv("GHCR_TOKEN")
        }
    }
}
