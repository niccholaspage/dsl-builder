package com.example

class StaticDynamicValue<T>(private val value: T) : DynamicValue<T> {
    override fun compute(context: Context): T {
        return value
    }
}