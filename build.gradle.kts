val taskGroupName = "surfer"

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    idea
    id("org.graalvm.buildtools.native") version "0.10.2"
}

group = "one.tain"
version = "1.18-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.1.112.Final")
    implementation("io.jpower.kcp:kcp-netty:1.5.2")

    //ssl server support
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    //kotlin-logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

idea {
    module{
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}


kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("MainKt")
}
tasks.register("github") {
    group = taskGroupName
    dependsOn(tasks.getByName("build"))
    dependsOn(tasks.getByName("jvmDockerBuildx"))
}

tasks.register<Exec>("jvmDockerBuildx") {
    group = taskGroupName
    dependsOn(tasks.getByName("build"))
    if(properties["release"]=="true"){
        dependsOn(tasks.getByName("dockerLogin"))
    }
    val arguments= listOfNotNull(
        "docker",
        "buildx",
        "build",
        "--platform",
        "linux/amd64,linux/arm/v7,linux/arm64/v8,linux/ppc64le,linux/s390x,windows/amd64",
        "-t",
        "selcarpa/surfer:$version",
        if(properties["release"]=="true"){
            "--push"
        }else{
            null
        },
        "-t",
        "selcarpa/surfer:latest",
        "."
    )
    commandLine(
        arguments
    )
}

tasks.register<Exec>("dockerLogin") {
    group = taskGroupName
    commandLine(
        "docker",
        "login",
        "-u",
        "${properties["dockerUserName"]}",
        "-p",
        "${properties["dockerPassword"]}"
    )
}

