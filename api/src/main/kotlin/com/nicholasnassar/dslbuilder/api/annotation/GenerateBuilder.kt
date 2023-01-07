package com.nicholasnassar.dslbuilder.api.annotation

/**
 * This annotation can be placed on any class you would
 * like to generate a builder for.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateBuilder