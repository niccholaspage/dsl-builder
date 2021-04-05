# DSL Builder
DSL Builder is a compile-time dependency that automatically generates DSLs based on annotations attached to your Kotlin classes and properties. By using Google’s Kotlin Symbol Processing API to hook into the Kotlin compiler, DSL Builder can process program source code in Kotlin for annotated classes utilizing Square’s KotlinPoet library.

On every commit of DSL Builder, GitHub Actions automatically builds and publishes updated Maven packages to version 0.0.1-SNAPSHOT. More documentation coming soon!

Code sample:
```kotlin
package com.example

import com.nicholasnassar.dslbuilder.api.annotation.GenerateBuilder

@GenerateBuilder
open class Pet(val name: String, val breed: String)

fun main() {
    val person = PersonBuilder().apply {
        firstName = "Nicholas"
        lastName = "Nassar"

        pet {
            name = "ZuZu"
            breed = "Yorkie"
        }
    }.build()

    println("Hi, I'm ${person.firstName} ${person.lastName}!")
}
```
