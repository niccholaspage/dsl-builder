plugins {
    id("com.google.devtools.ksp")
    kotlin("jvm")
}

version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

sourceSets {
    main {
        java {
            srcDir(file("sample/build/generated/ksp/src/main/kotlin"))
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
