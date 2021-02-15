package com.nicholasnassar.dslbuilder

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.nicholasnassar.dslbuilder.annotation.GenerateBuilder
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

class GenerateBuilderProcessor : SymbolProcessor {
    companion object {
        private const val DYNAMIC_VALUE_SUFFIX = "DynamicValue"
    }

    private lateinit var codeGenerator: CodeGenerator
    private lateinit var logger: KSPLogger

    private lateinit var contextClass: ClassName
    private lateinit var dynamicValueClass: ClassName
    private lateinit var staticDynamicValueClass: ClassName
    private lateinit var computedDynamicValueClass: ClassName
    private lateinit var rollingDynamicValueClass: ClassName
    private lateinit var dslMarkerAnnotationClass: ClassName

    private val generateBuilderAnnotation = GenerateBuilder::class.java.canonicalName

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
        val typeVariableNames: List<TypeVariableName>,
        val collectionType: TypeName,
        val collectionTypeSingleItemName: String,
    ) {
        val dependencyFiles = mutableSetOf<KSFile>()
    }

    class ClassInfo(
        val dependencies: Dependencies,
        val builderClassName: ClassName,
        val classBuilder: TypeSpec.Builder
    )

    private val builderClassesToWrite = mutableMapOf<ClassName, ClassInfo>()

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
            val valueType = collectionBuilderInfo.collectionType
            val mutableCollectionType = mutableCollectionClass.parameterizedBy(valueType)
            val dependencyFiles = collectionBuilderInfo.dependencyFiles.toTypedArray()

            val packageName = builderClassName.packageName
            val className = builderClassName.simpleName
            val file = codeGenerator.createNewFile(
//                Dependencies(true, *dependencyFiles),
                Dependencies.ALL_FILES,
                packageName, className
            )

            val classBuilder = TypeSpec.classBuilder(className)

            classBuilder.addTypeVariables(collectionBuilderInfo.typeVariableNames)

            classBuilder.addAnnotation(dslMarkerAnnotationClass)

            classBuilder.primaryConstructor(
                FunSpec.constructorBuilder().addParameter(ParameterSpec("parentCollection", mutableCollectionType))
                    .build()
            )

            classBuilder.addProperty(
                PropertySpec.builder("parentCollection", mutableCollectionType, KModifier.PRIVATE)
                    .initializer("parentCollection").build()
            )

            val functionName = collectionBuilderInfo.collectionTypeSingleItemName.decapitalize()

            classBuilder.addFunction(
                FunSpec.builder(functionName)
                    .addParameter("value", valueType).addCode("parentCollection.add(value)").build()
            )

            val builderClassInfo = builderClassesToWrite[valueType]

            if (builderClassInfo != null) {
                classBuilder.addFunction(
                    FunSpec.builder(functionName).addParameter(
                        ParameterSpec.builder(
                            "init",
                            LambdaTypeName.get(builderClassInfo.builderClassName, emptyList(), unitClass)
                        ).build()
                    ).addCode("parentCollection.add(%T().apply(init).build())", builderClassInfo.builderClassName)
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

        return TypeVariableName(name.asString(), variance)
    }

    private fun KSTypeArgument.asTypeName(): TypeName {
        val argumentType = type ?: return STAR

        return argumentType.asTypeName()
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

    private fun generateCollectionLambda(
        kotlinClass: KSClassDeclaration,
        containingFile: KSFile,
        propertyName: String,
        propertyTypeName: ParameterizedTypeName
    ): FunSpec? {
        val typeArgument = propertyTypeName.typeArguments[0]

        val rawTypeArgumentClass = when (typeArgument) {
            is ClassName -> typeArgument
            is ParameterizedTypeName -> typeArgument.rawType
            is WildcardTypeName -> null
            else -> throw IllegalArgumentException("????")
        }

        if (rawTypeArgumentClass != null) {
            val currentPackageName = rawTypeArgumentClass.packageName

            val updatedPackageName = if (currentPackageName == "kotlin" || currentPackageName.startsWith("kotlin.")) {
                rawTypeArgumentClass.packageName.replaceFirst("kotlin", "com.nicholasnassar.dslbuilder.kotlin")
            } else {
                rawTypeArgumentClass.packageName
            }

            val typeVariableNames = kotlinClass.typeParameters.map { it.asTypeVariableName() }

            val collectionTypeSingleItemName = rawTypeArgumentClass.simpleName
            val multiBuilderClass = ClassName(updatedPackageName, collectionTypeSingleItemName + "sBuilder")
            collectionBuildersToGenerate.getOrPut(multiBuilderClass) {
                CollectionBuilderInfo(typeVariableNames, typeArgument, collectionTypeSingleItemName)
            }.dependencyFiles.add(containingFile)
            val builderLambda =
                LambdaTypeName.get(multiBuilderClass, emptyList(), unitClass)

            return FunSpec.builder(propertyName).addParameter("init", builderLambda)
                .addCode(
                    """
                        %T($propertyName).apply(init)
                    """.trimIndent(), multiBuilderClass
                ).build()
        } else {
            return null
        }
    }

    fun generateProperty(kotlinClass: KSClassDeclaration, containingFile: KSFile, classBuilder: TypeSpec.Builder, parameter: KSValueParameter): Boolean {
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
            // If we have a collection, we should generate a nice property that accepts a lambda allowing
            // you to add items to a collection.
            classBuilder.addProperty(generateCollectionProperty(propertyName, propertyTypeName))

            val collectionLambda = generateCollectionLambda(kotlinClass, containingFile, propertyName, propertyTypeName)

            if (collectionLambda != null) {
                classBuilder.addFunction(collectionLambda)
            }
        } else {
            classBuilder.addProperty(generateBasicProperty(propertyName, propertyTypeName))
        }

        return isDynamic
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

    private fun generateBuildFunction(
        baseClassType: ClassName,
        parametersInConstructor: List<KSValueParameter>,
        typeVariableNames: List<TypeVariableName>
    ): FunSpec {
        val codeBlock = CodeBlock.builder()

        parametersInConstructor.filter { !it.type.resolve().isMarkedNullable }.forEach {
            val parameterName = it.name!!.asString()
            codeBlock.add("require($parameterName != null) { %S }\n", "$parameterName cannot be null!")
        }

        codeBlock.add(
            "return %T(${parametersInConstructor.joinToString { it.name!!.asString() + "!!" }})",
            baseClassType
        )

        val returnType = if (typeVariableNames.isEmpty()) {
            baseClassType
        } else {
            baseClassType.parameterizedBy(typeVariableNames)
        }

        return FunSpec.builder("build").returns(returnType).addCode(codeBlock.build()).build()
    }

    inner class BuilderVisitor : KSVisitorVoid() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            classDeclaration.primaryConstructor!!.accept(this, data)
        }

        override fun visitFunctionDeclaration(function: KSFunctionDeclaration, data: Unit) {
            val parent = function.parentDeclaration as KSClassDeclaration
            val packageName = parent.containingFile!!.packageName.asString()
            val baseClassName = parent.simpleName.asString()
            val builderClassName = ClassName(packageName, "${baseClassName}Builder")
            val baseClassType = ClassName(packageName, baseClassName)
            val containingFile = function.containingFile!!

            val classBuilder = TypeSpec.classBuilder(builderClassName)

            val typeVariableNames = parent.typeParameters.map { it.asTypeVariableName() }

            typeVariableNames.forEach {
                classBuilder.addTypeVariable(it)
            }

            classBuilder.addAnnotation(dslMarkerAnnotationClass)

            val dynamicValues = mutableSetOf<String>()

            // Theoretically, we override the class declaration visit method and only accept
            // on a class's primary constructor, so the parameters below should only refer to
            // the properties in the primary constructor.
            function.parameters.forEach { parameter ->
                if (generateProperty(parent, containingFile, classBuilder, parameter)) {
                    dynamicValues.add(parameter.name!!.asString())
                }
            }

            classBuilder.addFunction(generateBuildFunction(baseClassType, function.parameters, typeVariableNames))

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