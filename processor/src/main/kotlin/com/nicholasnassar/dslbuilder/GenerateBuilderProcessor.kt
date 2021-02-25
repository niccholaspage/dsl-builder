package com.nicholasnassar.dslbuilder

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.nicholasnassar.dslbuilder.api.Builder
import com.nicholasnassar.dslbuilder.api.annotation.BuilderModifier
import com.nicholasnassar.dslbuilder.api.annotation.GenerateBuilder
import com.nicholasnassar.dslbuilder.api.annotation.NullValue
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

class GenerateBuilderProcessor : SymbolProcessor {
    companion object {
        private const val DYNAMIC_VALUE_SUFFIX = "DynamicValue"

        private val STAR_PROJECTION = WildcardTypeName.producerOf(ANY.copy(nullable = true))

        private val GENERATE_BUILDER_ANNOTATION = GenerateBuilder::class.java.canonicalName
        private val NULL_VALUE_ANNOTATION = NullValue::class.java.canonicalName

        private val BUILDER_INTERFACE_NAME = Builder::class.java.run {
            ClassName(`package`.name, simpleName)
        }

        private val MUTABLE_COLLECTION_CLASSES = ClassName("kotlin.collections", "MutableCollection")
        private val SET_CLASS = ClassName("kotlin.collections", "Set")
        private val UNIT_CLASS = ClassName("kotlin", "Unit")
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
        val modifiers: List<BuilderModifier>,
        val hasTypeParameters: Boolean,
        val dependencies: Dependencies,
        val builderClassName: ClassName,
        val classBuilder: TypeSpec.Builder,
        val wrappedParameters: List<WrappedParameter>
    )

    private val builderClassesToWrite = mutableMapOf<ClassName, ClassInfo>()
    private val subTypes = mutableMapOf<ClassName, MutableList<ClassName>>()

    override fun finish() {
        builderClassesToWrite.forEach { (className, classInfo) ->
            val builderClass = classInfo.builderClassName

            val file =
                codeGenerator.createNewFile(classInfo.dependencies, builderClass.packageName, builderClass.simpleName)

            val fileBuilder = FileSpec.builder(builderClass.packageName, builderClass.simpleName)

            val classBuilder = classInfo.classBuilder

            val ktClass = resolver.getClassDeclarationByName(className.canonicalName)!!

            // One of the parameters has a builder,
            // so lets be nice and create a function
            // utilizing the builder.
            classInfo.wrappedParameters.forEach {
                val parameter = it.parameter

                val type = parameter.type.asTypeName()

                val rawType = if (type is ParameterizedTypeName) {
                    type.rawType
                } else {
                    type
                }

                val parameterName = parameter.name!!.asString()

                subTypes[rawType]?.forEach { subType ->
                    val functionName = subType.simpleName.decapitalize()
                    val fixedParameterName = parameterName.capitalize()

                    val newFunctionName = if (functionName.endsWith(fixedParameterName)) {
                        functionName
                    } else {
                        functionName + fixedParameterName
                    }

                    val typeArguments = if (classInfo.hasTypeParameters && type is ParameterizedTypeName) {
                        val rawTypeClassName = (rawType as ClassName).canonicalName

                        val superClassConstructorCall =
                            resolver.getClassDeclarationByName(subType.canonicalName)?.superTypes?.find { typeReference ->
                                val declaration = typeReference.resolve().declaration

                                declaration.qualifiedName!!.asString() == rawTypeClassName
                            }

                        superClassConstructorCall?.resolve()?.arguments?.zip(ktClass.typeParameters) { argument, typeParameter ->
                            val typeName = argument.asTypeName()

                            when (typeParameter.variance) {
                                Variance.CONTRAVARIANT -> WildcardTypeName.consumerOf(typeName)
                                Variance.COVARIANT -> WildcardTypeName.producerOf(typeName)
                                else -> typeName
                            }
                        }
                    } else {
                        null
                    }

                    classBuilder.addFunction(
                        generateBuilderForProperty(
                            newFunctionName,
                            parameterName,
                            ClassName(subType.packageName, getBuilderName(subType.simpleName)),
                            classInfo.builderClassName,
                            typeArguments
                        )
                    )
                }

                val builderClassInfo = builderClassesToWrite[type]

                if (builderClassInfo != null) {
                    classBuilder.addFunction(
                        generateBuilderForProperty(
                            parameterName,
                            parameterName,
                            builderClassInfo.builderClassName,
                            null,
                            null
                        )
                    )
                }
            }

            fileBuilder.addType(classBuilder.build())

            file.writer().use { outputSteam ->
                fileBuilder.build().writeTo(outputSteam)
            }

            file.close()
        }

        // Now, let's generate our builders for our collections.
        for ((builderClassName, collectionBuilderInfo) in collectionBuildersToGenerate) {
            val rawTypeClass =
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(collectionBuilderInfo.rawTypeArgumentClass.canonicalName))!!
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

            val collectionType = collectionBuilderInfo.collectionType

            val valueType = when {
                parameters.isEmpty() -> collectionType
                collectionType is ParameterizedTypeName -> collectionType.rawType.parameterizedBy(parameters)
                else -> throw IllegalArgumentException("weird exception at collection stuff.")
            }

            val mutableCollectionType = MUTABLE_COLLECTION_CLASSES.parameterizedBy(valueType)

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
                    .addParameter("value", valueType).addCode("parentCollection.add(value)").build()
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

                    val typeParameters = ktClass.typeParameters.map { it.asTypeVariableName() }

                    val superClassDeclaration = superClassConstructorCall.resolve().declaration

                    val superClassName = ClassName(
                        superClassDeclaration.packageName.asString(),
                        superClassDeclaration.simpleName.asString()
                    )

                    val superClassInfo = builderClassesToWrite[superClassName]

                    val superClassTypeParameters =
                        if (superClassInfo != null && superClassInfo.modifiers.contains(BuilderModifier.OPEN_COLLECTION_GENERIC)) {
                            superClassConstructorCall.resolve().declaration.typeParameters.map {
                                WildcardTypeName.producerOf(it.asTypeVariableName())
                            }
                        } else {
                            val declarationTypeParameters = superClassDeclaration.typeParameters

                            superClassConstructorCall.resolve().arguments.zip(declarationTypeParameters) { typeArgument, typeParameter ->
                                val typeName = typeArgument.asTypeName()

                                if (typeName is ClassName) {
                                    val typeVariableName = typeParameter.asTypeVariableName()

                                    val variance = when (typeParameter.variance) {
                                        Variance.CONTRAVARIANT -> KModifier.IN
                                        Variance.COVARIANT -> KModifier.OUT
                                        else -> null
                                    }

                                    if (variance != null) {
                                        TypeVariableName(typeVariableName.name, typeVariableName.bounds, variance)
                                    } else {
                                        typeName
                                    }
                                } else {
                                    typeName
                                }
                            }
                        }

                    val beginning = ClassName(
                        packageName,
                        className
                    )

                    val receiverType = if (superClassTypeParameters.isEmpty()) {
                        beginning
                    } else {
                        beginning.parameterizedBy(superClassTypeParameters)
                    }

                    val parameterizedBuilderClass = if (typeParameters.isEmpty()) {
                        builderClass
                    } else {
                        builderClass.parameterizedBy(typeParameters)
                    }

                    val listType = MUTABLE_COLLECTION_CLASSES.parameterizedBy(
                        valueType.rawType.parameterizedBy(MutableList(valueType.typeArguments.size) { STAR_PROJECTION })
                    )

                    classBuilder.addFunction(
                        FunSpec.builder(it.simpleName.decapitalize()).receiver(receiverType)
                            .addParameter(
                                ParameterSpec(
                                    "init",
                                    LambdaTypeName.get(parameterizedBuilderClass, emptyList(), UNIT)
                                )
                            )
                            .addCode(
                                "(parentCollection as %T).add(%T().apply(init).build())",
                                listType,
                                parameterizedBuilderClass
                            )
                            .build()
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
                    builderClassInfo.builderClassName.parameterizedBy(valueType.typeArguments)
                } else {
                    builderClassInfo.builderClassName
                }

                classBuilder.addFunction(
                    FunSpec.builder(functionName).addParameter(
                        ParameterSpec.builder(
                            "init",
                            LambdaTypeName.get(returnType, emptyList(), UNIT_CLASS)
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
        val symbols = resolver.getSymbolsWithAnnotation(GENERATE_BUILDER_ANNOTATION)
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

        when (val declaration = type.declaration) {
            is KSClassDeclaration -> {
                val packageName = declaration.packageName.asString()
                val simpleName = declaration.simpleName.asString()

                val typeParameters = type.arguments.map { it.asTypeName() }

                return if (typeParameters.isNotEmpty()) {
                    ClassName(packageName, simpleName).parameterizedBy(typeParameters)
                } else {
                    ClassName(packageName, simpleName)
                }
            }
            is KSTypeParameter -> {
                return declaration.asTypeVariableName()
            }
            else -> {
                throw IllegalArgumentException("Unsupported type")
            }
        }
    }

    private fun generateBuilderForProperty(
        functionName: String,
        parameterName: String,
        builderTypeName: TypeName,
        receiverClass: ClassName?,
        typeParameters: List<TypeName>?,
    ): FunSpec {
        val builder = FunSpec.builder(functionName)
            .addParameter(
                ParameterSpec.builder(
                    "init",
                    LambdaTypeName.get(builderTypeName, emptyList(), UNIT_CLASS)
                ).build()
            )

        if (receiverClass != null && typeParameters != null) {
            builder.receiver(receiverClass.parameterizedBy(typeParameters))
            builder.addCode("(this as %T).", receiverClass.parameterizedBy(typeParameters))
        }

        builder.addCode("$parameterName = %T().apply(init).build()", builderTypeName)

        return builder.build()
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
            LambdaTypeName.get(returnType, emptyList(), UNIT_CLASS)

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
        numberOfTypeVariables: Int,
    ): FunSpec {
        val codeBody = if (dynamicValues.isEmpty()) {
            "return emptySet()"
        } else {
            "return setOf(${dynamicValues.joinToString { "instance.$it" }}).filterNotNull().toSet()"
        }

        val parameterType = if (numberOfTypeVariables == 0) {
            baseClassType
        } else {
            baseClassType.parameterizedBy(MutableList(numberOfTypeVariables) { STAR })
        }

        return FunSpec.builder("getImmediateDynamicValues")
            .addParameter("instance", parameterType)
            .returns(SET_CLASS.parameterizedBy(dynamicValueClass.parameterizedBy(STAR))).addCode(codeBody)
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

        return FunSpec.builder("build").addModifiers(KModifier.OVERRIDE).returns(returnType).addCode(codeBlock.build())
            .build()
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

            val typeVariableNames = parent.typeParameters.map {
                val typeVariableName = it.asTypeVariableName()

                TypeVariableName(typeVariableName.name, typeVariableName.bounds, null)
            }

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
                    annotation.annotationType.resolve().declaration.qualifiedName?.asString() == NULL_VALUE_ANNOTATION
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

            // Let's add our implementation of the Builder interface so consumers
            // of the dsl-builder API can store different types of builders, including
            // the ability to use generics!
            classBuilder.addSuperinterface(BUILDER_INTERFACE_NAME)

            classBuilder.addFunction(generateBuildFunction(baseClassType, wrappedParameters, typeVariableNames))

            classBuilder.addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        generateImmediateDynamicValuesGetter(
                            baseClassType,
                            dynamicValues,
                            typeVariableNames.size
                        )
                    ).build()
            )

            val annotation =
                parent.annotations.find { it.annotationType.resolve().declaration.qualifiedName!!.asString() == GENERATE_BUILDER_ANNOTATION }!!

            val modifiersArgument = annotation.arguments.find { it.name!!.asString() == "modifiers" }!!

            val modifiers = (modifiersArgument.value as List<KSType>).map {
                BuilderModifier.valueOf(it.declaration.simpleName.asString())
            }

            builderClassesToWrite[baseClassType] = ClassInfo(
                modifiers,
                parent.typeParameters.isNotEmpty(),
                Dependencies(true, containingFile),
//                Dependencies.ALL_FILES,
                builderClassName,
                classBuilder,
                wrappedParameters
            )
        }
    }

}