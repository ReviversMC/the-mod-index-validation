plugins {
    id ("com.github.johnrengelman.shadow") version "7.1.2"
    kotlin("jvm") version "1.6.21"
}

group = "com.github.reviversmc.themodindex.validation"
version = "2.0.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api("com.github.reviversmc:the-mod-index-api:3.0.1")
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