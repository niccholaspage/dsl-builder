plugins {
    id("com.google.devtools.ksp")
    kotlin("jvm")
    idea
}

version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

idea {
    module {
        java {
            generatedSourceDirs.add(file("build/generated/ksp/main/kotlin"))
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":processor"))
    ksp(project(":processor"))
}

ksp {
    arg("dynamic_value_class", "com.example.DynamicValue")
}
