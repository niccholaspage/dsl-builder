package com.nicholasnassar.dslbuilder

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import com.nicholasnassar.dslbuilder.annotation.NullValue
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

class GenerateBuilderProcessor : SymbolProcessor {
    companion object {
        private const val DYNAMIC_VALUE_SUFFIX = "DynamicValue"
    }

    private lateinit var codeGenerator: CodeGenerator
    private lateinit var logger: KSPLogger
    private lateinit var resolver: Resolver

    private lateinit var contextClass: ClassName
    private lateinit var dynamicValueClass: ClassName
    private lateinit var staticDynamicValueClass: ClassName
    private lateinit var computedDynamicValueClass: ClassName
    private lateinit var rollingDynamicValueClass: ClassName
    private lateinit var dslMarkerAnnotationClass: ClassName

    private val generateBuilderAnnotation = GenerateBuilder::class.java.canonicalName
    private val nullValueAnnotation = NullValue::class.java.canonicalName

    private val mutableCollectionClass = ClassName("kotlin.collections", "MutableCollection")
    private val setClass = ClassName("kotlin.collections", "Set")
    private val unitClass = ClassName("kotlin", "Unit")

    private val collectionToMutableClasses = setOf("List", "Set").map {
        ClassName("kotlin.collections", it) to ClassName(
            "kotlin.collections",
            "Mutable$it"
        )
    }.toMap()

    private val collectionBuildersToGenerate = mutableMapOf<ClassName, CollectionBuilderInfo>()

    class CollectionBuilderInfo(
        val collectionType: TypeName,
        val rawTypeArgumentClass: ClassName,
    ) {
        val dependencyFiles = mutableSetOf<KSFile>()
    }

    class ClassInfo(
        val dependencies: Dependencies,
        val builderClassName: ClassName,
        val classBuilder: TypeSpec.Builder
    )

    private val builderClassesToWrite = mutableMapOf<ClassName, ClassInfo>()
    private val subTypes = mutableMapOf<ClassName, MutableList<ClassName>>()

    override fun finish() {
        builderClassesToWrite.forEach { (className, classInfo) ->
            val builderClass = classInfo.builderClassName

            val file =
                codeGenerator.createNewFile(classInfo.dependencies, builderClass.packageName, builderClass.simpleName)

            val fileBuilder = FileSpec.builder(builderClass.packageName, builderClass.simpleName)

            fileBuilder.addType(classInfo.classBuilder.build())

            file.writer().use { outputSteam ->
                fileBuilder.build().writeTo(outputSteam)
            }

            file.close()
        }

        // Now, let's generate our builders for our collections.
        for ((builderClassName, collectionBuilderInfo) in collectionBuildersToGenerate) {
            val rawTypeClass =
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(collectionBuilderInfo.rawTypeArgumentClass.canonicalName))!!

            val valueType = collectionBuilderInfo.collectionType
            val fixedTypeVariableName: TypeName

            val fixedType = if (valueType is ParameterizedTypeName) {
                fixedTypeVariableName = TypeVariableName(rawTypeClass.typeParameters[0].name.asString())

                valueType.rawType.parameterizedBy(fixedTypeVariableName)
            } else {
                fixedTypeVariableName = UNIT

                valueType
            }

            val mutableCollectionType = mutableCollectionClass.parameterizedBy(fixedType)
            val dependencyFiles = collectionBuilderInfo.dependencyFiles.toTypedArray()

            val packageName = builderClassName.packageName
            val className = builderClassName.simpleName
            val file = codeGenerator.createNewFile(
//                Dependencies(true, *dependencyFiles),
                Dependencies.ALL_FILES,
                packageName, className
            )

            val classBuilder = TypeSpec.classBuilder(className)
            val parameters = rawTypeClass.typeParameters.map {
                val typeVariableName = it.asTypeVariableName()

                TypeVariableName(typeVariableName.name, typeVariableName.bounds, null)
            }

            classBuilder.addTypeVariables(parameters)

            classBuilder.addAnnotation(dslMarkerAnnotationClass)

            classBuilder.primaryConstructor(
                FunSpec.constructorBuilder().addParameter(ParameterSpec("parentCollection", mutableCollectionType))
                    .build()
            )

            classBuilder.addProperty(
                PropertySpec.builder("parentCollection", mutableCollectionType, KModifier.PRIVATE)
                    .initializer("parentCollection").build()
            )

            val functionName = collectionBuilderInfo.rawTypeArgumentClass.simpleName.decapitalize()

            classBuilder.addFunction(
                FunSpec.builder(functionName)
                    .addParameter("value", fixedType).addCode("parentCollection.add(value)").build()
            )

            if (valueType is ParameterizedTypeName) {
                subTypes[valueType.rawType]?.forEach {
                    val builderClass = ClassName(it.packageName, getBuilderName(it.simpleName))

                    val normalClass = ClassName(it.packageName, it.simpleName)

                    val ktClass =
                        resolver.getClassDeclarationByName(resolver.getKSNameFromString(normalClass.canonicalName))!!

                    val superClassConstructorCall = ktClass.superTypes.find { typeReference ->
                        val declaration = typeReference.resolve().declaration

                        declaration == rawTypeClass
                    }!!

                    val typeParameters = superClassConstructorCall.resolve().arguments.map { argument ->
                        val typeName = argument.asTypeName()

                        if (typeName is ClassName) {
                            WildcardTypeName.producerOf(typeName)
                        } else {
                            typeName
                        }
                    }

                    val beginning = ClassName(
                        packageName,
                        className
                    )

                    val receiverType = if (typeParameters.isEmpty()) {
                        beginning
                    } else {
                        beginning.parameterizedBy(typeParameters)
                    }

                    val listType = mutableCollectionClass.parameterizedBy(valueType.rawType.parameterizedBy(typeParameters))

                    classBuilder.addFunction(
                        FunSpec.builder(it.simpleName.decapitalize()).receiver(receiverType)
                            .addParameter(ParameterSpec("init", LambdaTypeName.get(builderClass, emptyList(), UNIT)))
                            .addCode("(parentCollection as %T).add(%T().apply(init).build())", listType, builderClass).build()
                    )
                }
            } else {
                subTypes[valueType]?.forEach {
                    val builderClass = ClassName(it.packageName, getBuilderName(it.simpleName))

                    classBuilder.addFunction(
                        FunSpec.builder(it.simpleName.decapitalize())
                            .addParameter(ParameterSpec("init", LambdaTypeName.get(builderClass, emptyList(), UNIT)))
                            .addCode("parentCollection.add(%T().apply(init).build())", builderClass).build()
                    )
                }
            }

            val rawValueType = if (valueType is ParameterizedTypeName) {
                valueType.rawType
            } else {
                valueType
            }


            val builderClassInfo = builderClassesToWrite[rawValueType]

            if (builderClassInfo != null) {
                val returnType = if (valueType is ParameterizedTypeName) {
                    builderClassInfo.builderClassName.parameterizedBy(fixedTypeVariableName)
                } else {
                    builderClassInfo.builderClassName
                }

                classBuilder.addFunction(
                    FunSpec.builder(functionName).addParameter(
                        ParameterSpec.builder(
                            "init",
                            LambdaTypeName.get(returnType, emptyList(), unitClass)
                        ).build()
                    ).addCode("parentCollection.add(%T().apply(init).build())", returnType)
                        .build()
                )
            }

            val fileBuilder = FileSpec.builder(packageName, className)

            fileBuilder.addType(classBuilder.build())

            file.writer().use { outputStream ->
                fileBuilder.build().writeTo(outputStream)
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

        val dslMarkerAnnotationClassName = options["dsl_marker_annotation_class"]
        require(dslMarkerAnnotationClassName != null) { "dsl_marker_annotation_class cannot be null!" }
        dslMarkerAnnotationClass = ClassName.bestGuess(dslMarkerAnnotationClassName)
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        this.resolver = resolver
        val symbols = resolver.getSymbolsWithAnnotation(generateBuilderAnnotation)
        val ret = symbols.filter { !it.validate() }
        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .map { it.accept(BuilderVisitor(), Unit) }
        return ret
    }

    private fun KSTypeParameter.asTypeVariableName(): TypeVariableName {
        val variance = when (variance) {
            Variance.COVARIANT -> KModifier.OUT
            Variance.CONTRAVARIANT -> KModifier.IN
            else -> null
        }

        return TypeVariableName(name.asString(), this.bounds.map { it.asTypeName() }, variance)
    }

    private fun KSTypeArgument.asTypeName(): TypeName {
        val argumentType = type ?: return STAR

        return when (variance) {
            Variance.CONTRAVARIANT -> WildcardTypeName.consumerOf(argumentType.asTypeName())
            Variance.COVARIANT -> WildcardTypeName.producerOf(argumentType.asTypeName())
            else -> argumentType.asTypeName()
        }
    }

    private fun KSTypeReference.asTypeName(): TypeName {
        val type = resolve()
        val packageName = type.declaration.packageName.asString()
        val simpleName = type.declaration.simpleName.asString()

        val typeParameters = type.arguments.map { it.asTypeName() }

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

    private fun generateCollectionProperty(
        propertyName: String,
        propertyTypeName: ParameterizedTypeName
    ): PropertySpec {
        val singleValueType = propertyTypeName.typeArguments[0]

        val mutableCollectionType =
            collectionToMutableClasses[propertyTypeName.rawType]!!.parameterizedBy(singleValueType)

        val simpleCollectionName = propertyTypeName.rawType.simpleName

        return PropertySpec.builder(propertyName, mutableCollectionType)
            .addModifiers(KModifier.PRIVATE)
            .initializer("mutable${simpleCollectionName}Of()").build()
    }

    private fun generateBasicProperty(propertyName: String, propertyTypeName: TypeName): PropertySpec {
        return PropertySpec.builder(propertyName, propertyTypeName.copy(nullable = true)).mutable(true)
            .initializer("null")
            .build()
    }

    private fun convertCanonicalNameToMultiBuilder(className: ClassName): ClassName {
        val currentPackageName = className.packageName

        val updatedPackageName = if (currentPackageName == "kotlin" || currentPackageName.startsWith("kotlin.")) {
            className.packageName.replaceFirst("kotlin", "com.nicholasnassar.dslbuilder.kotlin")
        } else {
            className.packageName
        }

        return ClassName(updatedPackageName, className.simpleName + "sBuilder")
    }

    private fun generateCollectionLambda(
        containingFile: KSFile,
        propertyName: String,
        propertyTypeName: ParameterizedTypeName
    ): FunSpec {
        val typeArgument = propertyTypeName.typeArguments[0]

        val rawTypeArgumentClass = when (typeArgument) {
            is ClassName -> typeArgument
            is ParameterizedTypeName -> typeArgument.rawType
            else -> throw IllegalArgumentException("????")
        }

        val multiBuilderClass = convertCanonicalNameToMultiBuilder(rawTypeArgumentClass)
        collectionBuildersToGenerate.getOrPut(multiBuilderClass) {
            CollectionBuilderInfo(typeArgument, rawTypeArgumentClass)
        }.dependencyFiles.add(containingFile)

        val returnType = if (typeArgument is ParameterizedTypeName) {
            multiBuilderClass.parameterizedBy(typeArgument.typeArguments)
        } else {
            multiBuilderClass
        }

        val builderLambda =
            LambdaTypeName.get(returnType, emptyList(), unitClass)

        return FunSpec.builder(propertyName).addParameter("init", builderLambda)
            .addCode(
                """
                    %T($propertyName).apply(init)
                """.trimIndent(), returnType
            ).build()
    }

    fun generateProperty(
        containingFile: KSFile,
        classBuilder: TypeSpec.Builder,
        wrappedParameter: WrappedParameter
    ) {
        val propertyName = wrappedParameter.parameter.name!!.asString()
        val propertyType = wrappedParameter.parameter.type
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

        if (propertyTypeName is ParameterizedTypeName && collectionToMutableClasses.containsKey(propertyTypeName.rawType)) {
            // If we have a collection, we should generate a nice property that accepts a lambda allowing
            // you to add items to a collection.
            classBuilder.addProperty(generateCollectionProperty(propertyName, propertyTypeName))
            classBuilder.addFunction(
                generateCollectionLambda(
                    containingFile,
                    propertyName,
                    propertyTypeName
                )
            )
        } else {
            classBuilder.addProperty(generateBasicProperty(propertyName, propertyTypeName))
        }
    }

    private fun generateImmediateDynamicValuesGetter(
        baseClassType: ClassName,
        dynamicValues: Set<String>,
        isGeneric: Boolean
    ): FunSpec {
        val codeBody = if (dynamicValues.isEmpty()) {
            "return emptySet()"
        } else {
            "return setOf(${dynamicValues.joinToString { "instance.$it" }}).filterNotNull().toSet()"
        }

        return FunSpec.builder("getImmediateDynamicValues")
            .addParameter("instance", if (isGeneric) baseClassType.parameterizedBy(STAR) else baseClassType)
            .returns(setClass.parameterizedBy(dynamicValueClass.parameterizedBy(STAR))).addCode(codeBody)
            .build()
    }

    class WrappedParameter(
        val parameter: KSValueParameter,
        val isNotNullable: Boolean,
        val nullValue: String?,
        val isDynamic: Boolean
    )

    private fun generateBuildFunction(
        baseClassType: ClassName,
        parametersInConstructor: List<WrappedParameter>,
        typeVariableNames: List<TypeVariableName>
    ): FunSpec {
        val codeBlock = CodeBlock.builder()

        parametersInConstructor.filter { it.isNotNullable }.forEach {
            val parameterName = it.parameter.name!!.asString()

            if (it.nullValue == null) {
                codeBlock.add("require($parameterName != null) { %S }\n", "$parameterName cannot be null!")
            } else {
                if (it.isDynamic) {
                    codeBlock.add(
                        "if ($parameterName == null) { $parameterName = %T(${it.nullValue}) }\n",
                        staticDynamicValueClass
                    )
                } else {
                    codeBlock.add("if ($parameterName == null) { $parameterName = ${it.nullValue} }\n")
                }
            }
        }

        codeBlock.add(
            "return %T(${
                parametersInConstructor.joinToString {
                    val param = it.parameter.name!!.asString()

                    if (it.isNotNullable) {
                        "$param!!"
                    } else {
                        param
                    }
                }
            })",
            baseClassType
        )

        val returnType = if (typeVariableNames.isEmpty()) {
            baseClassType
        } else {
            baseClassType.parameterizedBy(typeVariableNames)
        }

        return FunSpec.builder("build").returns(returnType).addCode(codeBlock.build()).build()
    }

    fun getBuilderName(simpleName: String): String {
        return "${simpleName}Builder"
    }

    inner class BuilderVisitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.primaryConstructor!!.accept(this, data)
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            val parent = function.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val baseClassName = parent.simpleName.asString()
            val builderClassName = ClassName(packageName, getBuilderName(baseClassName))
            val baseClassType = ClassName(packageName, baseClassName)
            val containingFile = function.containingFile!!

            val superTypes = parent.superTypes

            if (superTypes.isNotEmpty()) {
                val superTypeNames = superTypes.map { it.asTypeName() }

                val subClass = ClassName(packageName, baseClassName)

                superTypeNames.forEach {
                    val rawClass = when (it) {
                        is ParameterizedTypeName -> it.rawType
                        is ClassName -> it
                        else -> return@forEach
                    }

                    val superClass =
                        ClassName(rawClass.packageName, rawClass.simpleName)

                    subTypes.getOrPut(superClass) { mutableListOf() }.add(subClass)
                }
            }

            val classBuilder = TypeSpec.classBuilder(builderClassName)

            val typeVariableNames = parent.typeParameters.map { it.asTypeVariableName() }

            classBuilder.addTypeVariables(typeVariableNames)

            classBuilder.addAnnotation(dslMarkerAnnotationClass)

            val dynamicValues = mutableSetOf<String>()

            // Theoretically, we override the class declaration visit method and only accept
            // on a class's primary constructor, so the parameters below should only refer to
            // the properties in the primary constructor.
            val wrappedParameters = function.parameters.map { parameter ->
                val propertyName = parameter.name!!.asString()
                val propertyType = parameter.type
                val propertyTypeName = propertyType.asTypeName()

                val nullValueAnnotation = parameter.annotations.find { annotation ->
                    annotation.annotationType.resolve().declaration.qualifiedName?.asString() == nullValueAnnotation
                }

                val nullValue = if (nullValueAnnotation != null) {
                    nullValueAnnotation.arguments.find { argument -> argument.name!!.asString() == "nullValue" }!!.value as String
                } else {
                    null
                }

                val isNotNullable = propertyType.resolve().nullability == Nullability.NOT_NULL

                val isDynamic =
                    propertyName.endsWith(DYNAMIC_VALUE_SUFFIX) && propertyTypeName is ParameterizedTypeName && propertyTypeName.rawType.canonicalName == dynamicValueClass.canonicalName

                if (isDynamic) {
                    dynamicValues.add(parameter.name!!.asString())
                }

                val wrappedParameter = WrappedParameter(parameter, isNotNullable, nullValue, isDynamic)

                generateProperty(containingFile, classBuilder, wrappedParameter)

                wrappedParameter
            }

            classBuilder.addFunction(generateBuildFunction(baseClassType, wrappedParameters, typeVariableNames))

            classBuilder.addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        generateImmediateDynamicValuesGetter(
                            baseClassType,
                            dynamicValues,
                            typeVariableNames.isNotEmpty()
                        )
                    ).build()
            )

            builderClassesToWrite[baseClassType] = ClassInfo(
                Dependencies(true, containingFile),
//                Dependencies.ALL_FILES,
                builderClassName,
                classBuilder
            )
        }
    }

}