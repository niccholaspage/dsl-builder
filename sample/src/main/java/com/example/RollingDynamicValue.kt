package com.example

class RollingDynamicValue<T>(private val computeLambda: (Context) -> T) : DynamicValue<T> {
    override fun compute(context: Context): T = computeLambda(context)
}