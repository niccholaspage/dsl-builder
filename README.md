# DSL Builder

DSL Builder is a compile-time dependency that automatically generates DSLs based on annotations attached to your Kotlin
classes and properties. By using Google’s Kotlin Symbol Processing API to hook into the Kotlin compiler, DSL Builder can
process program source code in Kotlin for annotated classes utilizing Square’s KotlinPoet library.

Here is a quick code sample to give you an idea of how you can use DSL Builder:

```kotlin
package com.example

import com.nicholasnassar.dslbuilder.api.annotation.GenerateBuilder

@GenerateBuilder
class Person(val firstName: String, val lastName: String, val pet: Pet2)

@GenerateBuilder
open class Pet(val name: String, val breed: String)

fun main() {
    val person = PersonBuilder().apply {
        firstName = "Nicholas"
        lastName = "Nassar"

        pet {
            name = "Pepper"
            breed = "Domestic Shorthair"
        }
    }.build()

    println("Hi, I'm ${person.firstName} ${person.lastName}! I have a cat called ${person.pet.name}!")
}
```

## Usage

DSL Builder use's [Google's KSP library](https://github.com/google/ksp) to find all classes you have annotated
with ```@GenerateBuilder``` and generate builder classes for them. To get started, you will need to go into your
build.gradle.kts file and add the KSP plugin by
following [this guide](https://kotlinlang.org/docs/ksp-quickstart.html#use-your-own-processor-in-a-project) from the
Kotlin
website. Once this is done, you will need to add the DSL Builder API and KSP dependencies to your dependencies list,
like so:

```kotlin
dependencies {
    implementation("com.nicholasnassar.dslbuilder:dsl-builder-api:0.0.2")
    ksp("com.nicholasnassar.dslbuilder:dsl-builder-ksp:0.0.2")
}
```

TODO: Talk about options that need to be passed to processors.
Finally, to make your IDE aware of the generated code from KSP, you will need to follow the guide
available [here](https://kotlinlang.org/docs/ksp-quickstart.html#make-ide-aware-of-generated-code). This
will allow for the generated builder classes to show up in autocompletion in your editor, and will stop usages of those
classes from causing errors in the IDE.

## Sample Project

DSL Builder is currently very experimental and is definitely not suited for actual production use. If you would like to
see a sample project, take a look [here](https://github.com/niccholaspage/dsl-builder-sample/)!