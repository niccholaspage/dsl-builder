package com.nicholasnassar.dslbuilder.annotation

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class NullValue(val nullValue: String)