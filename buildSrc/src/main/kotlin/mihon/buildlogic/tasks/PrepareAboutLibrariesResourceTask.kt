package mihon.buildlogic.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class PrepareAboutLibrariesResourceTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val outputFile = outputDirectory.file("raw/aboutlibraries.json").get().asFile
        outputFile.parentFile.mkdirs()
        Files.copy(sourceFile.get().asFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
