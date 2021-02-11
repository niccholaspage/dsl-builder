package com.example

import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import com.nicholasnassar.dslbuilder.annotation.Value
import java.io.OutputStream

@GenerateBuilder
class AClass(private val outputStream: OutputStream, @Value val b: String, val c: Double) {

}