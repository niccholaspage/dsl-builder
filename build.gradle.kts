import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "1.8.0"
    id("com.vanniktech.maven.publish.base") version "0.23.1" apply false
    id("org.jetbrains.dokka") version "1.7.20"
}

allprojects {
    group = "com.nicholasnassar.dslbuilder"
    version = "0.0.2-SNAPSHOT"

    pluginManager.withPlugin("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(16))
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(16)
        }
    }

    plugins.withId("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {
            publishToMavenCentral(SonatypeHost.S01, true)

            signAllPublications()

            pom {
                name.set("DSL Builder")
                description.set("A compile-time dependency that automatically generates DSLs based on annotations attached to your Kotlin classes and properties.")
                inceptionYear.set("2021")
                url.set("https://github.com/niccholaspage/dsl-builder/")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }

                    scm {
                        url.set("https://github.com/niccholaspage/dsl-builder/")
                        connection.set("scm:git:git://github.com/niccholaspage/dsl-builder.git")
                        developerConnection.set("scm:git:ssh://git@github.com/niccholaspage/dsl-builder.git")
                    }

                    developers {
                        developer {
                            id.set("niccholaspage")
                            name.set("Nicholas Nassar")
                        }
                    }
                }
            }
        }
    }
}

repositories {
    mavenCentral()
}