package com.example

import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import com.nicholasnassar.dslbuilder.annotation.Value
import java.io.OutputStream

@GenerateBuilder
class AClass(
    private val outputStream: OutputStream,
    @Value val b: String,
    @Value val cooldownDynamicValue: DynamicValue<Double>,
    @Value val coolGrades: List<Double>,
) {

}