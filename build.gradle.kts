import com.github.gradle.node.npm.task.NpmTask
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
    kotlin("plugin.serialization") version "2.3.10"
    kotlin("plugin.spring") version "2.3.10"
    kotlin("plugin.jpa") version "2.3.10"
    id("dev.detekt") version "2.0.0-alpha.2"
    id("org.springframework.boot") version "4.0.3"
    id("com.google.cloud.tools.jib") version "3.5.3"
    id("io.github.simonhauck.release") version "1.5.1"
    id("com.github.node-gradle.node") version "7.0.2"
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
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${libs.versions.spring.cloud.get()}"))

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
    implementation(libs.resilience4j.micrometer)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.ktor.client.apache5)
    implementation(libs.ktor.client.java)
    implementation(libs.nzb.streamer)
    implementation(libs.sentry.spring.boot)
    implementation(libs.sentry.logback)
    implementation(libs.pgmq.kotlin.jvm)
    implementation(libs.spring.cloud.context)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

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
    testImplementation(libs.mock.nntp.server)
    testImplementation(libs.spring.boot.starter.security.test)
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

// Emit META-INF/build-info.properties so the running JVM can report its
// own version via Spring's BuildProperties bean.
springBoot {
    buildInfo()
}

tasks.withType<JibTask>().configureEach {
    notCompatibleWithConfigurationCache("because https://github.com/GoogleContainerTools/jib/issues/3132")
}

// --- Frontend build ---
// Builds the React frontend (debridav-frontend submodule) and bundles its
// static output into the Spring Boot JAR under /static/, so the backend
// serves the UI at /. Pin is tracked as a git submodule — run
// `git submodule update --init` on a fresh clone (or
// `actions/checkout@v4` with `submodules: true` in CI). Skipped if the
// submodule isn't checked out or -PskipFrontend=true is passed.

val frontendDir = file("debridav-frontend")
val frontendStaticOutput = layout.buildDirectory.dir("generated/frontend/static")
val skipFrontend = providers.gradleProperty("skipFrontend").map { it == "true" }.orElse(false)
val hasFrontend = frontendDir.resolve("package.json").exists()

node {
    version.set("22.12.0")
    download.set(true)
    workDir.set(layout.buildDirectory.dir("nodejs"))
    npmWorkDir.set(layout.buildDirectory.dir("npm"))
    nodeProjectDir.set(frontendDir)
}

// Cache-incompat reason shared across the frontend pipeline below. The
// node-gradle plugin (npmInstall, NpmTask) captures Project references at
// execution time, and our own onlyIf closures here also reference script
// state (`skipFrontend`, `hasFrontend`), which isn't serializable. Rather
// than fight it, mark each task as incompatible — matches the approach
// already taken for Jib higher up in this file.
val frontendCcReason =
    "captures script/project references (node-gradle plugin + onlyIf closures)"

tasks.npmInstall {
    notCompatibleWithConfigurationCache(frontendCcReason)
    onlyIf { !skipFrontend.get() && hasFrontend }
}

val frontendBuild by tasks.registering(NpmTask::class) {
    notCompatibleWithConfigurationCache(frontendCcReason)
    description = "Build frontend static assets"
    group = "frontend"
    onlyIf { !skipFrontend.get() && hasFrontend }
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir(frontendDir.resolve("src")).optional()
    inputs.dir(frontendDir.resolve("public")).optional()
    inputs.files(
        frontendDir.resolve("package.json"),
        frontendDir.resolve("vite.config.ts"),
        frontendDir.resolve("tsconfig.json"),
        frontendDir.resolve("tsconfig.app.json"),
        frontendDir.resolve("tsconfig.node.json"),
        frontendDir.resolve("index.html"),
    ).optional()
    outputs.dir(frontendDir.resolve("dist"))
}

val copyFrontend by tasks.registering(Copy::class) {
    notCompatibleWithConfigurationCache(frontendCcReason)
    description = "Copy built frontend into resources"
    group = "frontend"
    onlyIf { !skipFrontend.get() && hasFrontend }
    dependsOn(frontendBuild)
    from(frontendDir.resolve("dist"))
    into(frontendStaticOutput)
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/frontend"))
}

tasks.processResources {
    dependsOn(copyFrontend)
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
