package com.nicholasnassar.dslbuilder.api.annotation

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateBuilder(vararg val modifiers: BuilderModifier)