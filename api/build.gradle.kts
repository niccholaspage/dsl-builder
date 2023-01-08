import org.jetbrains.dokka.gradle.DokkaTask
import java.net.URL

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets {
        named("main") {
            moduleName.set("DSL Builder API")
            sourceLink {
                localDirectory.set(file("src/main/kotlin"))
                remoteUrl.set(URL("https://github.com/niccholaspage/dsl-builder/tree/main/api/" +
                        "/src/main/kotlin"
                ))
                remoteLineSuffix.set("#L")
            }
        }
    }
}

mavenPublishing {
    coordinates("com.nicholasnassar.dslbuilder", "dsl-builder-api", version.toString())
}