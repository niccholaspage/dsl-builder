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
            url = uri("https://repo.nicholasnassar.com/")
            credentials {
                username = System.getenv("REPO_USER")
                password = System.getenv("REPO_PASSWORD")
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