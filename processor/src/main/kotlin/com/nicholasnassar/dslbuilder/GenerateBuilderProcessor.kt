package com.nicholasnassar.dslbuilder

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import com.nicholasnassar.dslbuilder.annotation.Value
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.OutputStream

fun OutputStream.appendText(str: String) {
    this.write(str.toByteArray())
}

class GenerateBuilderProcessor : SymbolProcessor {
    lateinit var codeGenerator: CodeGenerator
    lateinit var logger: KSPLogger
    lateinit var dynamicValueClass: ClassName

    private val generateBuilderAnnotation = GenerateBuilder::class.java.canonicalName
    private val valueAnnotation = Value::class.java.canonicalName

    override fun finish() {

    }

    override fun init(
        options: Map<String, String>,
        kotlinVersion: KotlinVersion,
        codeGenerator: CodeGenerator,
        logger: KSPLogger
    ) {
        this.codeGenerator = codeGenerator
        this.logger = logger

        val dynamicValueClassName = options["dynamic_value_class"]

        require(dynamicValueClassName != null) { "dynamic_value_class cannot be null!" }

        dynamicValueClass = ClassName.bestGuess(dynamicValueClassName)
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(generateBuilderAnnotation)
        val ret = symbols.filter { !it.validate() }
        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .map { it.accept(BuilderVisitor(), Unit) }
        return ret
    }

    private fun KSTypeReference.asTypeName(): TypeName {
        val type = resolve()
        val packageName = type.declaration.packageName.asString()
        val simpleName = type.declaration.simpleName.asString()

        val typeParameters = type.arguments.map { it.type!!.asTypeName() }

        return if (typeParameters.isNotEmpty()) {
            ClassName(packageName, simpleName).parameterizedBy(typeParameters)
        } else {
            ClassName(packageName, simpleName)
        }
    }

    fun generateProperty(classBuilder: TypeSpec.Builder, parameter: KSValueParameter) {
        val propertyName = parameter.name!!.asString()
        val propertyType = parameter.type

        classBuilder.addProperty(
            PropertySpec.builder(propertyName, propertyType.asTypeName().copy(nullable = true)).mutable(true)
                .initializer("null")
                .build()
        )
    }

    inner class BuilderVisitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.primaryConstructor!!.accept(this, data)
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            val parent = function.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val className = "${parent.simpleName.asString()}Builder"
            val file =
                codeGenerator.createNewFile(Dependencies(true, function.containingFile!!), packageName, className)

            val fileBuilder = FileSpec.builder(packageName, className)

            val classBuilder = TypeSpec.classBuilder(className)

            function.parameters.forEach { parameter ->
                if (parameter.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == valueAnnotation }) {
                    generateProperty(classBuilder, parameter)
                }
            }

            fileBuilder.addType(classBuilder.build())

            file.writer().use {
                fileBuilder.build().writeTo(it)
            }

            file.close()
        }
    }

}