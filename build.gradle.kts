plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish") version "0.13.0" apply false
    id("org.jetbrains.dokka") version "1.4.20" apply false
}

repositories {
    mavenCentral()
    google()
}

