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
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/niccholaspage/dsl-builder")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
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