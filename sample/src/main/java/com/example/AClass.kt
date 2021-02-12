package com.example

import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import java.io.OutputStream

@GenerateBuilder
class AClass(
    private val outputStream: OutputStream?,
    val b: String,
    val cooldownDynamicValue: DynamicValue<Double>,
    val coolGrades: List<Double>,
) {
    val pizzaHut: Double = 5.0
}