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

    private val setClass = ClassName("kotlin.collections", "Set")
    private val starProjection = WildcardTypeName.producerOf(
        ANY.copy(
            true
        )
    )

    private val collectionToMutableClasses = setOf("List", "Set").map {
        ClassName("kotlin.collections", it) to ClassName(
            "kotlin.collections",
            "Mutable$it"
        )
    }.toMap()

    class ClassInfo(
        val dependencies: Dependencies,
        val packageName: String,
        val className: String,
        val classBuilder: TypeSpec.Builder
    )

    private val classesToWrite = mutableListOf<ClassInfo>()

    override fun finish() {
        classesToWrite.forEach {
            val packageName = it.packageName
            val className = it.className
            val classBuilder = it.classBuilder

            val file =
                codeGenerator.createNewFile(it.dependencies, packageName, className)

            val fileBuilder = FileSpec.builder(packageName, className)

            fileBuilder.addType(classBuilder.build())

            file.writer().use { outputSteam ->
                fileBuilder.build().writeTo(outputSteam)
            }

            file.close()
        }
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

    private fun generateCollectionProperty(propertyName: String, propertyTypeName: ParameterizedTypeName): PropertySpec {
        val singleValueType = propertyTypeName.typeArguments[0]

        val mutableCollectionType =
            collectionToMutableClasses[propertyTypeName.rawType]!!.parameterizedBy(singleValueType)

        val simpleCollectionName = propertyTypeName.rawType.simpleName

        return PropertySpec.builder(propertyName, mutableCollectionType)
                .initializer("mutable${simpleCollectionName}Of()").build()
    }

    private fun generateBasicProperty(propertyName: String, propertyTypeName: TypeName): PropertySpec {
        return PropertySpec.builder(propertyName, propertyTypeName.copy(nullable = true)).mutable(true)
            .initializer("null")
            .build()
    }

    private fun generateCollectionAddFunction(propertyName: String, singleValueType: TypeName): FunSpec {
        return FunSpec.builder(propertyName.dropLast(1)).addParameter("value", singleValueType).addCode(
            """
                        $propertyName.add(value)
                    """.trimIndent()
        ).build()
    }

    fun generateProperty(classBuilder: TypeSpec.Builder, parameter: KSValueParameter): Boolean {
        val propertyName = parameter.name!!.asString()
        val propertyType = parameter.type
        val propertyTypeName = propertyType.asTypeName()
        var isDynamic = false

        if (propertyName.endsWith(DYNAMIC_VALUE_SUFFIX) && propertyTypeName is ParameterizedTypeName && propertyTypeName.rawType.canonicalName == dynamicValueClass.canonicalName) {
            isDynamic = true

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

        if (propertyTypeName is ParameterizedTypeName && collectionToMutableClasses.containsKey(propertyTypeName.rawType)) {
            classBuilder.addProperty(generateCollectionProperty(propertyName, propertyTypeName))
            classBuilder.addFunction(generateCollectionAddFunction(propertyName, propertyTypeName))
        } else {
            classBuilder.addProperty(generateBasicProperty(propertyName, propertyTypeName))
        }

        return isDynamic
    }

    inner class BuilderVisitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.primaryConstructor!!.accept(this, data)
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            val parent = function.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val className = "${parent.simpleName.asString()}Builder"

            val classBuilder = TypeSpec.classBuilder(className)

            val dynamicValues = mutableSetOf<String>()

            function.parameters.forEach { parameter ->
                if (parameter.annotations.any { it.annotationType.resolve().declaration.qualifiedName?.asString() == valueAnnotation }) {
                    if (generateProperty(classBuilder, parameter)) {
                        dynamicValues.add(parameter.name!!.asString())
                    }
                }
            }

            val codeBody = if (dynamicValues.isEmpty()) {
                "return emptySet()"
            } else {
                "return setOf(${dynamicValues.joinToString()}).filterNotNull().toSet()"
            }

            classBuilder.addFunction(
                FunSpec.builder("getImmediateDynamicValues")
                    .returns(
                        setClass.parameterizedBy(
                            dynamicValueClass.parameterizedBy(starProjection)
                        )
                    ).addCode(codeBody).build()
            )

            classesToWrite.add(
                ClassInfo(
                    Dependencies(true, function.containingFile!!),
                    packageName,
                    className,
                    classBuilder
                )
            )
        }
    }

}