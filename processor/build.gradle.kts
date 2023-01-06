val kspVersion: String by project

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "com.nicholasnassar.dslbuilder"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":api"))
    implementation(kotlin("stdlib"))
    implementation("com.squareup:kotlinpoet:1.7.2")
    implementation("com.google.devtools.ksp:symbol-processing-api:$kspVersion")
}

sourceSets.main {
    java.srcDirs("src/main/kotlin")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = group as String
            artifactId = "dsl-builder-ksp"
            version = project.version as String

            from(components["java"])
        }
    }
}