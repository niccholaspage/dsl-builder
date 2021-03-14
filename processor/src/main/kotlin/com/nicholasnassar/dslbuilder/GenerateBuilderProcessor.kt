package com.nicholasnassar.dslbuilder

import com.google.devtools.ksp.getAllSuperTypes
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
        val dependencies: Dependencies,
        val builderClassName: ClassName,
        val classBuilder: TypeSpec.Builder,
        val wrappedParameters: List<WrappedParameter>
    )

    private val builderClassesToWrite = mutableMapOf<ClassName, ClassInfo>()
    private val subTypes = mutableMapOf<ClassName, MutableList<ClassName>>()

    private fun handleReceiverType(
        typeVariables: List<TypeVariableName>,
        necessaryTypeParameters: List<TypeName>,
        argumentTypes: List<TypeName>,
        rawTypeDeclaration: KSClassDeclaration
    ): List<TypeName>? {
        val receiverTypeArguments: MutableList<TypeName> =
            MutableList(typeVariables.size) { STAR_PROJECTION }

        necessaryTypeParameters.forEachIndexed { i, typeParameter ->
            val argumentType = argumentTypes[i]

            val varianceType = if (typeParameter is WildcardTypeName) {
                if (typeParameter.inTypes.isNotEmpty()) {
                    Variance.CONTRAVARIANT
                } else {
                    Variance.COVARIANT
                }
            } else {
                rawTypeDeclaration.typeParameters[i].variance
            }

            if (typeParameter != argumentType) {
                val argumentTypeClass = argumentType as ClassName

                val boundedType = if (typeParameter is TypeVariableName) {
                    val typeParameterIndex =
                        typeVariables.indexOfFirst { it.name == typeParameter.name }

                    if (typeParameter.bounds.isNotEmpty()) {
                        val bound = typeParameter.bounds[0] as ClassName

                        receiverTypeArguments[typeParameterIndex] = when (varianceType) {
                            Variance.CONTRAVARIANT -> WildcardTypeName.producerOf(argumentTypeClass)
                            Variance.COVARIANT -> WildcardTypeName.consumerOf(argumentTypeClass)
                            else -> argumentTypeClass
                        }

                        bound
                    } else {
                        ANY
                    }
                } else if (typeParameter is WildcardTypeName) {
                    when (varianceType) {
                        Variance.CONTRAVARIANT -> typeParameter.inTypes[0] as ClassName
                        Variance.COVARIANT -> typeParameter.outTypes[0] as ClassName
                        else -> throw java.lang.IllegalArgumentException("???")
                    }
                } else {
                    typeParameter as ClassName
                }

                val argumentTypeResolvedClass =
                    resolver.getClassDeclarationByName(argumentType.canonicalName)!!

                if (boundedType != argumentType && argumentTypeResolvedClass.getAllSuperTypes()
                        .all {
                            it.declaration.qualifiedName!!.asString() != boundedType.canonicalName
                        }
                ) {
                    return null
                }
            }
        }

        return receiverTypeArguments
    }

    class SubtypeInfo(val subType: ClassName, val builderClassName: ClassName, val receiverTypeArguments: List<TypeName>?)

    private fun getSubtypeInfoFor(type: TypeName, typeVariables: List<TypeVariableName>): List<SubtypeInfo> {
        val necessaryTypeParameters: List<TypeName>?

        val rawType = if (type is ParameterizedTypeName) {
            necessaryTypeParameters = type.typeArguments

            type.rawType
        } else {
            necessaryTypeParameters = null

            type
        } as ClassName

        val subtypeInfos = mutableListOf<SubtypeInfo>()

        subTypes[rawType]?.forEach subTypeLoop@{ subType ->
            val receiverTypeArguments: List<TypeName>

            if (type is ParameterizedTypeName) {
                val rawTypeClassName = rawType.canonicalName

                val superClassConstructorCall =
                    resolver.getClassDeclarationByName(subType.canonicalName)?.superTypes?.find { typeReference ->
                        val declaration = typeReference.resolve().declaration

                        declaration.qualifiedName!!.asString() == rawTypeClassName
                    }

                val superConstructorCallArguments = superClassConstructorCall?.resolve()?.arguments

                if (necessaryTypeParameters != null && superConstructorCallArguments != null) {
                    val rawTypeDeclaration = resolver.getClassDeclarationByName(rawTypeClassName)!!

                    val argumentTypes = superConstructorCallArguments.map { it.asTypeName() }

                    // Compare the type parameters from the field to the argument types of the class.
                    // If it doesn't fit then we return since we won't be able to generate a function
                    // for our parameter that sets it to this particular type.

                    val result = handleReceiverType(
                        typeVariables,
                        necessaryTypeParameters,
                        argumentTypes,
                        rawTypeDeclaration
                    )
                        ?: return@subTypeLoop

                    receiverTypeArguments = result
                } else {
                    receiverTypeArguments = listOf(ANY)
                }
            } else {
                receiverTypeArguments = listOf(ANY)
            }

            subtypeInfos.add(SubtypeInfo(subType, getBuilderClassName(subType), if (receiverTypeArguments.all { it == ANY }) null else receiverTypeArguments))
        }

        return subtypeInfos
    }

    override fun finish() {
        builderClassesToWrite.forEach { (className, classInfo) ->
            val builderClass = classInfo.builderClassName

            val file =
                codeGenerator.createNewFile(classInfo.dependencies, builderClass.packageName, builderClass.simpleName)

            val fileBuilder = FileSpec.builder(builderClass.packageName, builderClass.simpleName)

            val classBuilder = classInfo.classBuilder

            // One of the parameters has a builder,
            // so lets be nice and create a function
            // utilizing the builder.
            classInfo.wrappedParameters.forEach {
                val parameter = it.parameter

                val type = parameter.type.asTypeName()

                val parameterName = parameter.name!!.asString()

                val typeVariables = classBuilder.typeVariables

                val rawType = if (type is ParameterizedTypeName) {
                    type.rawType
                } else {
                    type
                } as ClassName

                val subtypeInfo = getSubtypeInfoFor(type, typeVariables)

                subtypeInfo.forEach { info ->
                    val functionName = info.subType.simpleName.decapitalize()
                    val fixedParameterName = parameterName.capitalize()

                    val newFunctionName = if (functionName.endsWith(fixedParameterName)) {
                        functionName
                    } else {
                        functionName + fixedParameterName
                    }

                    classBuilder.addFunction(
                        generateBuilderForProperty(
                            newFunctionName,
                            parameterName,
                            getBuilderClassName(info.subType),
                            classInfo.builderClassName,
                            info.receiverTypeArguments,
                            false
                        )
                    )
                }

                val builderClassInfo = builderClassesToWrite[rawType]

                if (builderClassInfo != null) {
                    val builderClassName = if (type is ParameterizedTypeName) {
                        builderClassInfo.builderClassName.parameterizedBy(type.typeArguments)
                    } else {
                        builderClassInfo.builderClassName
                    }

                    classBuilder.addFunction(
                        generateBuilderForProperty(
                            parameterName,
                            parameterName,
                            builderClassName,
                            null,
                            null,
                            false
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
            val rawType = collectionBuilderInfo.rawTypeArgumentClass
            val rawTypeClass =
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(rawType.canonicalName))!!
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

            val subtypeInfo = getSubtypeInfoFor(valueType, parameters)

            subtypeInfo.forEach { info ->
                classBuilder.addFunction(
                    generateBuilderForProperty(
                        info.subType.simpleName.decapitalize(),
                        "parentCollection",
                        getBuilderClassName(info.subType),
                        builderClassName,
                        info.receiverTypeArguments,
                        true
                    )
                )
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
                val typeParameters = type.arguments.map { it.asTypeName() }

                return if (typeParameters.isNotEmpty()) {
                    declaration.getClassName().parameterizedBy(typeParameters)
                } else {
                    declaration.getClassName()
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
        isCollectionMethod: Boolean
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
        }

        if (isCollectionMethod) {
            builder.addCode("$parameterName.add(%T().apply(init).build())", builderTypeName)
        } else {
            builder.addCode("$parameterName = %T().apply(init).build()", builderTypeName)
        }

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

    private fun getBuilderClassName(className: ClassName): ClassName {
        try {
            return builderClassesToWrite[className]!!.builderClassName
        } catch (e: Exception) {
            throw NullPointerException("asdf: $className")
        }
    }

    fun KSClassDeclaration.getClassName(): ClassName {
//        if (containingFile == null) {
//            return ClassName(packageName.asString(), simpleName.asString())
//        }

        //    val packageName = containingFile!!.packageName.asString()
        val packageName = packageName.asString()
        val simpleName = simpleName.asString()

        val parent = parentDeclaration

        return if (parent != null && parent is KSClassDeclaration) {
            val grandparentSimpleName = parent.simpleName.asString()
            ClassName(packageName, grandparentSimpleName).nestedClass(simpleName)
        } else {
            ClassName(packageName, simpleName)
        }
    }

    inner class BuilderVisitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.primaryConstructor!!.accept(this, data)
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            val parent = function.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val classSimpleName = parent.simpleName.asString()
            val containingFile = function.containingFile!!

            val baseClassName = parent.getClassName()

            val builderClassName = if (baseClassName != baseClassName.topLevelClassName()) {
                val grandparent = parent.parentDeclaration
                val grandparentSimpleName = grandparent!!.simpleName.asString()
                ClassName(packageName, grandparentSimpleName + classSimpleName + "Builder")
            } else {
                ClassName(packageName, classSimpleName + "Builder")
            }

            val superTypes = parent.superTypes

            if (superTypes.isNotEmpty()) {
                fun resolveSuperTypes(superTypes: List<KSTypeReference>) {
                    superTypes.forEach {
                        val declaration = it.resolve().declaration

                        if (declaration is KSClassDeclaration) {
                            resolveSuperTypes(declaration.superTypes)
                        }

                        val rawClass = when (val typeName = it.asTypeName()) {
                            is ParameterizedTypeName -> typeName.rawType
                            is ClassName -> typeName
                            else -> return@forEach
                        }

                        subTypes.getOrPut(rawClass) { mutableListOf() }.add(baseClassName)
                    }
                }

                resolveSuperTypes(parent.superTypes)
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

            classBuilder.addFunction(generateBuildFunction(baseClassName, wrappedParameters, typeVariableNames))

            classBuilder.addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        generateImmediateDynamicValuesGetter(
                            baseClassName,
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

            builderClassesToWrite[baseClassName] = ClassInfo(
                modifiers,
                Dependencies(true, containingFile),
                builderClassName,
//                Dependencies.ALL_FILES,
                classBuilder,
                wrappedParameters
            )
        }
    }

}