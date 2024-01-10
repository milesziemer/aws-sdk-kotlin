/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

import aws.sdk.kotlin.gradle.codegen.dsl.SmithyProjection
import aws.sdk.kotlin.gradle.codegen.dsl.generateSmithyProjections
import aws.sdk.kotlin.gradle.codegen.dsl.smithyKotlinPlugin
import aws.sdk.kotlin.gradle.codegen.smithyKotlinProjectionPath
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.ServiceShape
import java.nio.file.Paths
import java.util.*
import kotlin.streams.toList

plugins {
    kotlin("jvm") // FIXME - configuration doesn't resolve without this
    id("aws.sdk.kotlin.gradle.smithybuild")
}

description = "AWS SDK codegen tasks"

// get a project property by name if it exists (including from local.properties)
fun getProperty(name: String): String? {
    if (project.hasProperty(name)) {
        return project.properties[name].toString()
    } else if (project.ext.has(name)) {
        return project.ext[name].toString()
    }

    val localProperties = Properties()
    val propertiesFile: File = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use { localProperties.load(it) }

        if (localProperties.containsKey(name)) {
            return localProperties[name].toString()
        }
    }
    return null
}

// Represents information needed to generate a smithy projection JSON stanza
data class AwsService(
    /**
     * The service shape ID name
     */
    val serviceShapeId: String,
    /**
     * The package name to use for the service when generating smithy-build.json
     */
    val packageName: String,

    /**
     * The package version (this should match the sdk version of the project)
     */
    val packageVersion: String,

    /**
     * The path to the model file in aws-sdk-kotlin
     */
    val modelFile: File,

    /**
     * The name of the projection to generate
     */
    val projectionName: String,

    /**
     * The sdkId value from the service trait
     */
    val sdkId: String,

    /**
     * The model version from the service shape
     */
    val version: String,

    /**
     * A description of the service (taken from the title trait)
     */
    val description: String? = null,

)

val servicesProvider: Provider<List<AwsService>> = project.provider { discoverServices() }

// Manually create the projections rather than using the extension to avoid unnecessary configuration evaluation.
// Otherwise we would be reading the models from disk on every gradle invocation for unrelated projects/tasks
fun awsServiceProjections(): Provider<List<SmithyProjection>> {
    println("AWS service projection provider called")
    val p = servicesProvider.map {
        it.map { service ->
            SmithyProjection(
                service.projectionName,
            ).apply {
                val importPaths = mutableListOf(service.modelFile.absolutePath)
                if (file(service.modelExtrasDir).exists()) {
                    importPaths.add(service.modelExtrasDir)
                }
                imports = importPaths
                transforms = (transformsForService(service) ?: emptyList()) + removeDeprecatedShapesTransform("2023-11-28")

                val packageSettingsFile = file(service.packageSettings)
                val packageSettings = if (packageSettingsFile.exists()) {
                    val node = Node.parse(packageSettingsFile.inputStream()).asObjectNode().get()
                    node.expectMember("sdkId", "${packageSettingsFile.absolutePath} does not contain member `sdkId`")
                    val packageSdkId = node.getStringMember("sdkId").get().value
                    check(service.sdkId == packageSdkId) { "${packageSettingsFile.absolutePath} `sdkId` ($packageSdkId) does not match expected `${service.sdkId}`" }
                    node
                } else {
                    Node.objectNode()
                }

                smithyKotlinPlugin {
                    serviceShapeId = service.serviceShapeId
                    packageName = service.packageName
                    packageVersion = service.packageVersion
                    packageDescription = service.description
                    sdkId = service.sdkId
                    buildSettings {
                        generateFullProject = false
                        generateDefaultBuildFiles = false
                    }
                    apiSettings {
                        enableEndpointAuthProvider = packageSettings.getBooleanMember("enableEndpointAuthProvider").orNull()?.value
                    }
                }
            }
        }
    }

    // get around class cast issues, listProperty implements what we need to pass this to `NamedObjectContainer`
    return p
}

// this will lazily evaluate the provider and only cause the models to be
// mapped if the tasks are actually needed
// NOTE: FYI evaluation still happens if you ask for the list of tasks or rebuild the gradle model in intellij
smithyBuild.projections.addAllLater(awsServiceProjections())

/**
 * This function retrieves Smithy transforms associated with the target service to be merged into generated
 * `smithy-build.json` files.  The transform file MUST live in <service dir>/transforms.
 * The JSON fragment MUST be a JSON object of a transform as described on this page:
 * https://awslabs.github.io/smithy/1.0/guides/building-models/build-config.html#transforms.
 *
 * Transform filenames should follow the convention of:
 * <transform operation>-<target shape(s)>.json
 * Example: renameShapes-MarketplaceCommerceAnalyticsException.json
 */
fun transformsForService(service: AwsService): List<String>? {
    val transformsDir = File(service.transformsDir)
    return transformsDir.listFiles()?.map { transformFile ->
        transformFile.readText()
    }
}

fun removeDeprecatedShapesTransform(removeDeprecatedShapesUntil: String): String = """
    {
        "name": "awsSmithyKotlinRemoveDeprecatedShapes",
        "args": {
            "until": "$removeDeprecatedShapesUntil"
        }
    }
""".trimIndent()

// The root namespace prefix for SDKs
val sdkPackageNamePrefix = "aws.sdk.kotlin.services."

val sdkVersion: String by project

val serviceMembership: Membership by lazy { parseMembership(getProperty("aws.services")) }
val protocolMembership: Membership by lazy { parseMembership(getProperty("aws.protocols")) }

fun fileToService(applyFilters: Boolean): (File) -> AwsService? = { file: File ->
    val filename = file.nameWithoutExtension
    val model = Model.assembler().addImport(file.absolutePath).assemble().result.get()
    val services: List<ServiceShape> = model.shapes(ServiceShape::class.java).sorted().toList()
    val service = services.singleOrNull() ?: error("Expected one service per aws model, but found ${services.size} in ${file.absolutePath}: ${services.map { it.id }}")
    val protocol = service.protocol()

    val serviceTrait = service
        .findTrait(software.amazon.smithy.aws.traits.ServiceTrait.ID)
        .map { it as software.amazon.smithy.aws.traits.ServiceTrait }
        .orNull()
        ?: error("Expected aws.api#service trait attached to model ${file.absolutePath}")

    val sdkId = serviceTrait.sdkId
    val packageName = sdkId.replace(" ", "")
        .replace("-", "")
        .lowercase()
        .kotlinNamespace()
    val packageDescription = "The AWS Kotlin client for $sdkId"

    when {
        applyFilters && !serviceMembership.isMember(filename, packageName) -> {
            logger.info("skipping ${file.absolutePath}, $filename/$packageName not a member of $serviceMembership")
            null
        }

        applyFilters && !protocolMembership.isMember(protocol) -> {
            logger.info("skipping ${file.absolutePath}, $protocol not a member of $protocolMembership")
            null
        }

        else -> {
            logger.info("discovered service: ${serviceTrait.sdkId}")
            AwsService(
                serviceShapeId = service.id.toString(),
                packageName = "$sdkPackageNamePrefix$packageName",
                packageVersion = sdkVersion,
                modelFile = file,
                projectionName = filename,
                sdkId = sdkId,
                version = service.version,
                description = packageDescription,
            )
        }
    }
}

/**
 * Returns an AwsService model for every JSON file found in directory defined by property `modelsDirProp`
 * @param applyFilters Flag indicating if models should be filtered to respect the `aws.services` and `aws.protocol`
 * membership tests
 */
fun discoverServices(applyFilters: Boolean = true): List<AwsService> {
    println("discover services called")
    val modelsDir: String by project
    return fileTree(project.file(modelsDir)).mapNotNull(fileToService(applyFilters))
}

// Returns the trait name of the protocol of the service
fun ServiceShape.protocol(): String =
    listOf(
        "aws.protocols#awsJson1_0",
        "aws.protocols#awsJson1_1",
        "aws.protocols#awsQuery",
        "aws.protocols#ec2Query",
        "aws.protocols#restJson1",
        "aws.protocols#restXml",
    ).first { protocol -> findTrait(protocol).isPresent }.split("#")[1]

// Class and functions for service and protocol membership for SDK generation
data class Membership(val inclusions: Set<String> = emptySet(), val exclusions: Set<String> = emptySet())

fun Membership.isMember(vararg memberNames: String): Boolean =
    memberNames.none(exclusions::contains) && (inclusions.isEmpty() || memberNames.any(inclusions::contains))

fun parseMembership(rawList: String?): Membership {
    if (rawList == null) return Membership()

    val inclusions = mutableSetOf<String>()
    val exclusions = mutableSetOf<String>()

    rawList.split(",").map { it.trim() }.forEach { item ->
        when {
            item.startsWith('-') -> exclusions.add(item.substring(1))
            item.startsWith('+') -> inclusions.add(item.substring(1))
            else -> inclusions.add(item)
        }
    }

    val conflictingMembers = inclusions.intersect(exclusions)
    require(conflictingMembers.isEmpty()) { "$conflictingMembers specified both for inclusion and exclusion in $rawList" }

    return Membership(inclusions, exclusions)
}

fun <T> java.util.Optional<T>.orNull(): T? = this.orElse(null)

/**
 * Remove characters invalid for Kotlin package namespace identifier
 */
fun String.kotlinNamespace(): String = split(".")
    .joinToString(separator = ".") { segment -> segment.filter { it.isLetterOrDigit() } }

/**
 * The project directory under `aws-sdk-kotlin/services`
 *
 * NOTE: this will also be the artifact name in the GAV coordinates
 */
val AwsService.destinationDir: String
    get() {
        val sanitizedName = sdkId.replace(" ", "").replace("-", "").lowercase()
        return rootProject.file("services/$sanitizedName").absolutePath
    }

/**
 * Service specific model extras
 */
val AwsService.modelExtrasDir: String
    get() = rootProject.file("$destinationDir/model").absolutePath

/**
 * Defines where service-specific transforms are located
 */
val AwsService.transformsDir: String
    get() = rootProject.file("$destinationDir/transforms").absolutePath

/**
 * Service specific package settings
 */
val AwsService.packageSettings: String
    get() = rootProject.file("$destinationDir/package.json").absolutePath

fun forwardProperty(name: String) {
    getProperty(name)?.let {
        System.setProperty(name, it)
    }
}

val codegen by configurations.getting
dependencies {
    codegen(project(":codegen:aws-sdk-codegen"))
    codegen(libs.smithy.cli)
    codegen(libs.smithy.model)
}

tasks.generateSmithyProjections {
    doFirst {
        forwardProperty("aws.partitions_file")
        forwardProperty("aws.user_agent.add_metadata")
    }
    allowUnknownTraits = true
}

val stageSdks = tasks.register("stageSdks") {
    group = "codegen"
    description = "relocate generated SDK(s) from build directory to services/ dir"
    dependsOn(tasks.generateSmithyProjections)
    doLast {
        val discoveredServices = servicesProvider.get()
        println("discoveredServices = ${discoveredServices.joinToString { it.sdkId }}")
        discoveredServices.forEach {
            // val projectionOutputDir = smithyBuild.projections.getByName(it.projectionName).projectionRootDir
            val projectionOutputDir = smithyBuild.smithyKotlinProjectionPath(it.projectionName).get()
            logger.info("copying $projectionOutputDir to ${it.destinationDir}")
            copy {
                from("$projectionOutputDir/src")
                into("${it.destinationDir}/generated-src")
            }
            copy {
                from("$projectionOutputDir/build.gradle.kts")
                into(it.destinationDir)
            }
        }
    }
}

tasks.register("bootstrap") {
    group = "codegen"
    description = "Generate AWS SDK's and register them with the build"

    dependsOn(tasks.generateSmithyProjections)
    finalizedBy(stageSdks)
}

/**
 * Represents a type for a model that is sourced from aws-models
 */
data class SourceModel(
    /**
     * The path in aws-models to the model file
     */
    val path: String,
    /**
     * The sdkId trait value from the model
     */
    val sdkId: String,
    /**
     * The service version from the model
     */
    val version: String,
) {
    /**
     * The model filename in aws-sdk-kotlin
     */
    val destFilename: String
        get() {
            val name = sdkId.replace(" ", "-").lowercase()
            return "$name.json"
        }
}

fun discoverSourceModels(repoPath: String): List<SourceModel> {
    val root = File(repoPath)
    val models = root.listFiles()
        ?.map { Paths.get(it.absolutePath, "smithy", "model.json").toFile() }
        ?.filter { it.exists() } ?: error("no models found in $root")

    return models.map { file ->
        val model = Model.assembler().addImport(file.absolutePath).assemble().result.get()
        val services: List<ServiceShape> = model.shapes(ServiceShape::class.java).sorted().toList()
        require(services.size == 1) { "Expected one service per aws model, but found ${services.size} in ${file.absolutePath}: ${services.map { it.id }}" }
        val service = services.first()
        val serviceApi = service.getTrait(software.amazon.smithy.aws.traits.ServiceTrait::class.java).orNull()
            ?: error("Expected aws.api#service trait attached to model ${file.absolutePath}")

        SourceModel(file.absolutePath, serviceApi.sdkId, service.version)
    }
}

fun discoverAwsModelsRepoPath(): String? {
    val discovered = rootProject.file("../aws-models")
    if (discovered.exists()) return discovered.absolutePath

    return getProperty("awsModelsDir")?.let { File(it) }?.absolutePath
}

/**
 * Synchronize upstream changes from aws-models repository.
 *
 * Steps to synchronize:
 * 1. Clone aws-models if not already cloned
 * 2. `cd <path/to/aws-models>`
 * 3. `git pull` to pull down the latest changes
 * 4. `cd <path/to/aws-sdk-kotlin>`
 * 5. Run `./gradlew syncAwsModels`
 * 6. Check in any new models as needed (view warnings generated at end of output)
 */
tasks.register("syncAwsModels") {
    group = "codegen"
    description = "Sync upstream changes from aws-models repo"

    doLast {
        println("syncing AWS models")
        val repoPath = discoverAwsModelsRepoPath() ?: error("Failed to discover path to aws-models. Explicitly set -PawsModelsDir=<path-to-local-repo>")
        val sourceModelsBySdkId = discoverSourceModels(repoPath).associateBy { it.sdkId }

        val existingModelsBySdkId = discoverServices(applyFilters = false).associateBy { it.sdkId }

        val existingSdkIdSet = existingModelsBySdkId.values.map { it.sdkId }.toSet()
        val sourceSdkIdSet = sourceModelsBySdkId.values.map { it.sdkId }.toSet()

        // sync known existing models
        val pairs = existingModelsBySdkId.values.mapNotNull { existing ->
            sourceModelsBySdkId[existing.sdkId]?.let { source -> Pair(source, existing) }
        }

        val modelsDir = project.file("aws-models")

        pairs.forEach { (source, existing) ->
            // ensure we don't accidentally take a new API version
            if (source.version != existing.version) error("upstream version of ${source.path} does not match destination of existing model version ${existing.modelFile.name}")

            println("syncing ${existing.modelFile.name} from ${source.path}")
            copy {
                from(source.path)
                into(modelsDir)
                rename { existing.modelFile.name }
            }
        }

        val orphaned = existingSdkIdSet - sourceSdkIdSet
        val newSources = sourceSdkIdSet - existingSdkIdSet

        // sync new models
        newSources.forEach { sdkId ->
            val source = sourceModelsBySdkId[sdkId]!!
            val dest = Paths.get(modelsDir.absolutePath, source.destFilename).toFile()
            println("syncing new model ${source.sdkId} to $dest")
            copy {
                from(source.path)
                into(modelsDir)
                rename { source.destFilename }
            }
        }

        // generate warnings at the end so they are more visible
        if (orphaned.isNotEmpty() || newSources.isNotEmpty()) {
            println("\nWarnings:")
        }

        // models with no upstream source in aws-models
        orphaned.forEach { sdkId ->
            val existing = existingModelsBySdkId[sdkId]!!
            logger.warn("Cannot find a model file for ${existing.modelFile.name} (sdkId=${existing.sdkId}) but it exists in aws-sdk-kotlin!")
        }

        // new since last sync
        newSources.forEach { sdkId ->
            val source = sourceModelsBySdkId[sdkId]!!
            logger.warn("${source.path} (sdkId=$sdkId) is new to aws-models since the last sync!")
        }
    }
}
