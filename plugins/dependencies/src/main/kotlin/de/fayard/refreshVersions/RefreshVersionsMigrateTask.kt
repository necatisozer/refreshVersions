package de.fayard.refreshVersions

import de.fayard.refreshVersions.core.internal.ArtifactVersionKeyReader
import de.fayard.refreshVersions.core.internal.RefreshVersionsConfigHolder
import de.fayard.refreshVersions.core.internal.getVersionPropertyName
import de.fayard.refreshVersions.core.internal.hasHardcodedVersion
import de.fayard.refreshVersions.core.internal.versions.writeMissingEntriesInVersionProperties
import de.fayard.refreshVersions.internal.DependencyMapping
import de.fayard.refreshVersions.internal.countDependenciesWithHardcodedVersions
import de.fayard.refreshVersions.internal.getArtifactNameToConstantMapping
import de.fayard.refreshVersions.internal.shouldBeIgnored
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.tasks.TaskAction

open class RefreshVersionsMigrateTask : DefaultTask() {

    @TaskAction
    fun taskAddMissingVersions() {
        check(project == project.rootProject) { "This task is designed to run on root project only." }

        val configurationsWithHardcodedDependencies: List<Configuration> = project.findHardcodedDependencies()

        val versionsMap = RefreshVersionsConfigHolder.readVersionsMap()
        val versionKeyReader = RefreshVersionsConfigHolder.versionKeyReader
        val newEntries: Map<String, ExternalDependency> = findMissingEntries(
            configurations = configurationsWithHardcodedDependencies,
            versionsMap = versionsMap,
            versionKeyReader = versionKeyReader
        )

        writeMissingEntriesInVersionProperties(newEntries)
        Thread.sleep(1000)

        val list = project.allprojects.flatMap { subProject ->
            subProject.configurations.filter { it in configurationsWithHardcodedDependencies }
                .flatMap { configuration ->
                    configuration.allDependencies
                        .filterIsInstance<ExternalDependency>()
                        .filter { d -> d in newEntries.values }
                        .map { DependencyWithContext(it, configuration, subProject) }
                }
        }

        val mapping = getArtifactNameToConstantMapping()
            .associate { it.groupArtifact()  to it.constantName }

        list.forEach {
            println(it.format(mapping))
        }
    }
}

internal data class DependencyWithContext(
    val dependency: ExternalDependency,
    val configuration: Configuration,
    val project: Project
) {
    fun format(mapping: Map<String, String>): String {
        val prefix = "${project.name}:${configuration.name}"
        if (dependency.groupArtifact() in mapping) {
            return "${mapping[dependency.groupArtifact()]}"
        } else {
            return "$prefix:${dependency.groupArtifact()}:${dependency.version}"

        }
    }
}

internal fun ExternalDependency.groupArtifact(): String =
    "$group:$name"

internal fun DependencyMapping.groupArtifact(): String =
    "$group:$artifact"

internal fun Project.findHardcodedDependencies(): List<Configuration> {
    val versionsMap = RefreshVersionsConfigHolder.readVersionsMap()
    val projectsWithHardcodedDependenciesVersions: List<Project> = rootProject.allprojects.filter {
        it.countDependenciesWithHardcodedVersions(versionsMap) > 0
    }

    return projectsWithHardcodedDependenciesVersions.flatMap { project ->
        project.configurations.filterNot { configuration ->
            configuration.shouldBeIgnored() || 0 == configuration.countDependenciesWithHardcodedVersions(
                versionsMap = versionsMap,
                versionKeyReader = RefreshVersionsConfigHolder.versionKeyReader
            )
        }
    }
}


internal fun findMissingEntries(
    configurations: List<Configuration>,
    versionsMap: Map<String, String>,
    versionKeyReader: ArtifactVersionKeyReader
): Map<String, ExternalDependency> {

    val dependencyMap = configurations.flatMap { configuration ->
        configuration.dependencies
            .filterIsInstance<ExternalDependency>()
            .filter { it.hasHardcodedVersion(versionsMap, versionKeyReader) && it.version != null }
            .map { dependency: ExternalDependency ->
                val versionKey = getVersionPropertyName(dependency.module, versionKeyReader)
                versionKey to dependency
            }
    }
    val newEntries = dependencyMap
        .groupBy({ it.first }, { it.second })
        .filter { entry -> entry.key !in versionsMap }
        .mapValues { entry -> entry.value.maxBy { it.version!! }!! }

    return newEntries
}
