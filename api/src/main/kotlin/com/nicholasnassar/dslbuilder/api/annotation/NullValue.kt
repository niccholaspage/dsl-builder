package com.nicholasnassar.dslbuilder.api.annotation

/**
 * An annotation that can be used to associate a default value
 * with a property so that if it is not specified, the default
 * value is used.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class NullValue(val nullValue: String)