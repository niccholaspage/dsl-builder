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
    companion object {
        private const val DYNAMIC_VALUE_SUFFIX = "DynamicValue"
    }

    lateinit var codeGenerator: CodeGenerator
    lateinit var logger: KSPLogger

    private lateinit var contextClass: ClassName
    private lateinit var dynamicValueClass: ClassName
    private lateinit var staticDynamicValueClass: ClassName
    private lateinit var computedDynamicValueClass: ClassName
    private lateinit var rollingDynamicValueClass: ClassName

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

        val contextClassName = options["context_class"]
        require(contextClassName != null) { "context_class cannot be null!" }
        contextClass = ClassName.bestGuess(contextClassName)

        val dynamicValueClassName = options["dynamic_value_class"]
        require(dynamicValueClassName != null) { "dynamic_value_class cannot be null!" }
        dynamicValueClass = ClassName.bestGuess(dynamicValueClassName)

        val staticDynamicValueClassName = options["static_dynamic_value_class"]
        require(staticDynamicValueClassName != null) { "static_dynamic_value_class cannot be null!" }
        staticDynamicValueClass = ClassName.bestGuess(staticDynamicValueClassName)

        val computedDynamicValueClassName = options["computed_dynamic_value_class"]
        require(computedDynamicValueClassName != null) { "computed_dynamic_value_class cannot be null!" }
        computedDynamicValueClass = ClassName.bestGuess(computedDynamicValueClassName)

        val rollingDynamicValueClassName = options["rolling_dynamic_value_class"]
        require(rollingDynamicValueClassName != null) { "rolling_dynamic_value_class cannot be null!" }
        rollingDynamicValueClass = ClassName.bestGuess(rollingDynamicValueClassName)
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

    private fun generateStaticPropertySettingDynamic(
        propertyName: String,
        staticPropertyName: String,
        staticValueType: TypeName
    ): PropertySpec {
        return PropertySpec.builder(staticPropertyName, staticValueType.copy(nullable = true)).mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder().addParameter("value", staticValueType).addCode(
                    """
                            $propertyName = if (value == null) {
                                null
                            } else {
                                %T(value)
                            }
                            field = value
                        """.trimIndent(), staticDynamicValueClass
                ).build()
            )
            .build()
    }

    private fun generateFunctionSettingDynamic(
        propertyName: String,
        functionName: String,
        staticValueType: TypeName,
        dynamicValueClass: TypeName,
    ): FunSpec {
        return FunSpec.builder(functionName)
            .addParameter("init", LambdaTypeName.get(contextClass, emptyList(), staticValueType))
            .addCode(
                """
                        $propertyName = %T(init)
                    """.trimIndent(), dynamicValueClass
            )
            .build()
    }

    fun generateProperty(classBuilder: TypeSpec.Builder, parameter: KSValueParameter) {
        val propertyName = parameter.name!!.asString()
        val propertyType = parameter.type
        val propertyTypeName = propertyType.asTypeName()

        if (propertyName.endsWith(DYNAMIC_VALUE_SUFFIX) && propertyTypeName is ParameterizedTypeName && propertyTypeName.rawType.canonicalName == dynamicValueClass.canonicalName) {
            val staticPropertyName = propertyName.substring(0, propertyName.length - DYNAMIC_VALUE_SUFFIX.length)

            val staticValueType = propertyTypeName.typeArguments[0]

            classBuilder.addProperty(
                generateStaticPropertySettingDynamic(
                    propertyName,
                    staticPropertyName,
                    staticValueType
                )
            )

            classBuilder.addFunction(
                generateFunctionSettingDynamic(
                    propertyName,
                    staticPropertyName,
                    staticValueType,
                    computedDynamicValueClass
                )
            )
            classBuilder.addFunction(
                generateFunctionSettingDynamic(
                    propertyName,
                    "rolling${staticPropertyName.capitalize()}",
                    staticValueType,
                    rollingDynamicValueClass
                )
            )
        }

        classBuilder.addProperty(
            PropertySpec.builder(propertyName, propertyTypeName.copy(nullable = true)).mutable(true)
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