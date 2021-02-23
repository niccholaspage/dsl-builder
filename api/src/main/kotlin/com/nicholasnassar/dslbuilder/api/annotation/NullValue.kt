package com.nicholasnassar.dslbuilder.api.annotation

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class NullValue(val nullValue: String)