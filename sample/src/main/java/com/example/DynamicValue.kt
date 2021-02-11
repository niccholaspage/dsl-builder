package com.example

interface DynamicValue<T> {
    fun init(context: Context) {}
    fun compute(context: Context): T
}