package com.example

interface DynamicValue<T> {
    fun init(context: Unit) {}
    fun compute(context: Unit): T
}