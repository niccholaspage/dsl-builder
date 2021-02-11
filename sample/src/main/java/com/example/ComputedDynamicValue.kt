package com.example

class ComputedDynamicValue<T>(private val computeLambda: (Context) -> T) : DynamicValue<T> {
    // Technically this could be done on init instead but we are simplifying our implementation in the
    // sample project.
    private var cachedValue: T? = null


    override fun compute(context: Context): T {
        return cachedValue ?: computeLambda(context).also { cachedValue = it }
    }
}