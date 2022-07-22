plugins {
    id ("com.github.johnrengelman.shadow") version "7.1.2"
    kotlin("jvm") version "1.7.10"
}

group = "com.github.reviversmc.themodindex.validation"
version = "4.3.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api("com.github.reviversmc:the-mod-index-api:7.2.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }

    jar {
        manifest {
            attributes["Main-Class"] = "${rootProject.group}.ValidationKt"
        }
    }

    shadowJar {
        archiveFileName.set(rootProject.name + "-" + rootProject.version + ".jar")
    }
}