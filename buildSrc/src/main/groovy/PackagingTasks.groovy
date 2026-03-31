import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

import java.nio.file.Files
import java.nio.file.StandardCopyOption

abstract class DownloadArtifact extends DefaultTask {
    @Input
    abstract Property<String> getSourceUrl()

    @OutputFile
    abstract RegularFileProperty getTargetFile()

    @TaskAction
    void download() {
        def output = targetFile.get().asFile
        output.parentFile.mkdirs()
        logger.lifecycle("Downloading ${sourceUrl.get()} -> ${output}")
        sourceUrl.get().toURL().withInputStream { input ->
            Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

abstract class ExtractArchive extends DefaultTask {
    @Inject
    abstract ExecOperations getExecOperations()

    @Inject
    abstract FileSystemOperations getFileSystemOperations()

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getArchiveFile()

    @OutputDirectory
    abstract DirectoryProperty getDestinationDir()

    @OutputDirectory
    abstract DirectoryProperty getScratchDir()

    @Input
    abstract Property<String> getArchiveKind()

    @Input
    @Optional
    abstract Property<String> getSevenZipBinary()

    @TaskAction
    void extract() {
        def archive = archiveFile.get().asFile
        def destination = destinationDir.get().asFile
        def scratch = scratchDir.get().asFile

        project.delete(destination)
        project.delete(scratch)
        destination.mkdirs()
        scratch.mkdirs()

        switch (archiveKind.get()) {
            case 'zip':
                fileSystemOperations.copy {
                    from project.zipTree(archive)
                    into destination
                }
                break
            case 'tgz':
                fileSystemOperations.copy {
                    from project.tarTree(project.resources.gzip(archive))
                    into destination
                }
                break
            case 'dmg':
                if (!sevenZipBinary.present) {
                    throw new GradleException("A resolved 7-Zip binary is required to extract DMG archives.")
                }
                run7ZipExtraction(archive, destination, scratch)
                break
            default:
                throw new GradleException("Unsupported archive kind: ${archiveKind.get()}")
        }
    }

    void run7ZipExtraction(File archive, File destination, File scratch) {
        def sevenZip = sevenZipBinary.get()
        execOperations.exec {
            executable = sevenZip
            args 'x', archive.absolutePath, "-o${scratch.absolutePath}", '-y'
        }

        def nestedArchives = project.fileTree(scratch).matching {
            include '**/*.tar', '**/*.pkg', '**/*.app', '**/*.zip'
        }.files

        if (!nestedArchives.isEmpty()) {
            nestedArchives.each { nested ->
                if (nested.isDirectory()) {
                    fileSystemOperations.copy {
                        from nested
                        into destination
                    }
                } else if (nested.name.endsWith('.app')) {
                    fileSystemOperations.copy {
                        from nested.parentFile
                        include nested.name + '/**'
                        into destination
                    }
                } else {
                    execOperations.exec {
                        executable = sevenZip
                        args 'x', nested.absolutePath, "-o${destination.absolutePath}", '-y'
                    }
                }
            }
        } else {
            fileSystemOperations.copy {
                from scratch
                exclude 'Applications', '**/Applications'
                into destination
            }
        }
    }
}

abstract class PatchIniTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getIniFile()

    @Input
    abstract ListProperty<String> getLinesToInject()

    @TaskAction
    void patchIni() {
        IniPatcher.patchIniFile(iniFile.get().asFile, linesToInject.get())
    }
}

abstract class WriteTextFile extends DefaultTask {
    @Input
    abstract Property<String> getContent()

    @OutputFile
    abstract RegularFileProperty getTargetFile()

    @TaskAction
    void writeFile() {
        def output = targetFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(content.get(), 'UTF-8')
    }
}

abstract class BuildSfxExe extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getSfxModule()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSfxConfig()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getArchive7z()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void buildExe() {
        def moduleFile = sfxModule.get().asFile
        if (!moduleFile.exists()) {
            throw new GradleException("7-Zip SFX module not found: ${moduleFile}. Provide -PsevenZipSfxModule=/path/to/7z.sfx")
        }

        def output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.withOutputStream { out ->
            [moduleFile, sfxConfig.get().asFile, archive7z.get().asFile].each { part ->
                part.withInputStream { input ->
                    input.transferTo(out)
                }
            }
        }
    }
}

final class IniPatcher {
    static void patchIniFile(File iniFile, List<String> linesToInject) {
        if (!iniFile.exists()) {
            throw new GradleException("Cannot patch missing ini file: ${iniFile}")
        }

        def existingLines = iniFile.readLines('UTF-8')
        def normalizedAdditions = linesToInject.collect { it.trim() }.findAll { !it.isEmpty() }
        def missingLines = normalizedAdditions.findAll { candidate -> !existingLines.contains(candidate) }

        if (missingLines.isEmpty()) {
            return
        }

        def vmArgsIndex = existingLines.indexOf('-vmargs')
        def updatedLines = []

        if (vmArgsIndex >= 0) {
            updatedLines.addAll(existingLines[0..vmArgsIndex])
            updatedLines.addAll(missingLines)
            if (vmArgsIndex + 1 < existingLines.size()) {
                updatedLines.addAll(existingLines[(vmArgsIndex + 1)..<existingLines.size()])
            }
        } else {
            updatedLines.addAll(existingLines)
            if (!updatedLines.isEmpty() && updatedLines.last().trim()) {
                updatedLines.add('')
            }
            updatedLines.addAll(missingLines)
        }

        iniFile.setText(updatedLines.join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
    }
}
