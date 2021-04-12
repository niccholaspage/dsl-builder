plugins {
    kotlin("jvm")
    `maven-publish`
}

repositories {
    mavenCentral()
}

group = "com.nicholasnassar.dslbuilder"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(kotlin("stdlib"))
}

publishing {
    repositories {
        maven {
            val releasesRepoUrl = "https://repo.nicholasnassar.com/releases"
            val snapshotsRepoUrl = "https://repo.nicholasnassar.com/snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            credentials {
                username = System.getenv("REPO_USER")
                password = System.getenv("REPO_PASSWORD")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }

    publications {
        create<MavenPublication>("maven") {
            groupId = group as String
            artifactId = "dsl-builder-api"
            version = project.version as String

            from(components["java"])
        }
    }
}