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

sourceSets {
    main {
        java {
            srcDir("build/generated/ksp/main/kotlin")
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":processor"))
    ksp(project(":processor"))
}

ksp {
    arg("context_class", "com.example.Context")
    arg("dynamic_value_class", "com.example.DynamicValue")
    arg("static_dynamic_value_class", "com.example.StaticDynamicValue")
    arg("computed_dynamic_value_class", "com.example.ComputedDynamicValue")
    arg("rolling_dynamic_value_class", "com.example.RollingDynamicValue")
}
