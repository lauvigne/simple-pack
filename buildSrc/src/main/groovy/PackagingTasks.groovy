import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
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
    abstract ListProperty<String> getOpenFileLines()

    @Input
    abstract MapProperty<String, String> getOptionValueAssignments()

    @Input
    abstract ListProperty<String> getVmArgsLines()

    @TaskAction
    void patchIni() {
        IniPatcher.patchIniFile(
            iniFile.get().asFile,
            openFileLines.get(),
            optionValueAssignments.get(),
            vmArgsLines.get()
        )
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
    static void patchIniFile(
        File iniFile,
        List<String> openFileLines,
        Map<String, String> optionValueAssignments,
        List<String> vmArgsLines
    ) {
        if (!iniFile.exists()) {
            throw new GradleException("Cannot patch missing ini file: ${iniFile}")
        }

        def updatedLines = iniFile.readLines('UTF-8')

        applyOptionValueAssignments(updatedLines, optionValueAssignments)
        insertLinesAfterOption(updatedLines, '-openFile', openFileLines)
        insertVmArgsLines(updatedLines, vmArgsLines)

        iniFile.setText(updatedLines.join(System.lineSeparator()) + System.lineSeparator(), 'UTF-8')
    }

    private static void applyOptionValueAssignments(List<String> lines, Map<String, String> assignments) {
        assignments.each { option, rawValue ->
            def optionName = option?.trim()
            def optionValue = rawValue?.trim()

            if (!optionName || optionValue == null || optionValue.isEmpty()) {
                return
            }

            def optionIndex = lines.indexOf(optionName)
            if (optionIndex >= 0) {
                if (optionIndex + 1 < lines.size() && !isOptionLine(lines[optionIndex + 1])) {
                    lines[optionIndex + 1] = optionValue
                } else {
                    lines.add(optionIndex + 1, optionValue)
                }
            } else {
                def insertionIndex = insertionIndexBeforeVmArgs(lines)
                lines.add(insertionIndex, optionName)
                lines.add(insertionIndex + 1, optionValue)
            }
        }
    }

    private static void insertLinesAfterOption(List<String> lines, String option, List<String> additions) {
        def normalizedAdditions = additions.collect { it.trim() }.findAll { !it.isEmpty() }
        if (normalizedAdditions.isEmpty()) {
            return
        }

        def insertionIndex = findInsertionIndexAfterOption(lines, option)
        normalizedAdditions.each { candidate ->
            if (!lines.contains(candidate)) {
                lines.add(insertionIndex, candidate)
                insertionIndex++
            }
        }
    }

    private static void insertVmArgsLines(List<String> lines, List<String> vmArgsLines) {
        def normalizedAdditions = vmArgsLines.collect { it.trim() }.findAll { !it.isEmpty() }
        if (normalizedAdditions.isEmpty()) {
            return
        }

        def vmArgsIndex = lines.indexOf('-vmargs')
        def insertionIndex = vmArgsIndex >= 0 ? vmArgsIndex + 1 : insertionIndexBeforeVmArgs(lines)

        if (vmArgsIndex < 0 && !lines.isEmpty() && lines.last().trim()) {
            lines.add('')
            insertionIndex = lines.size()
        }

        normalizedAdditions.each { candidate ->
            if (!lines.contains(candidate)) {
                lines.add(insertionIndex, candidate)
                insertionIndex++
            }
        }
    }

    private static int findInsertionIndexAfterOption(List<String> lines, String option) {
        def optionIndex = lines.indexOf(option)
        if (optionIndex < 0) {
            return insertionIndexBeforeVmArgs(lines)
        }

        def insertionIndex = optionIndex + 1
        if (insertionIndex < lines.size() && !isOptionLine(lines[insertionIndex])) {
            insertionIndex++
        }

        return insertionIndex
    }

    private static int insertionIndexBeforeVmArgs(List<String> lines) {
        def vmArgsIndex = lines.indexOf('-vmargs')
        return vmArgsIndex >= 0 ? vmArgsIndex : lines.size()
    }

    private static boolean isOptionLine(String line) {
        return line?.trim()?.startsWith('-')
    }
}

final class IniPropertyParsers {
    static Map<String, String> parseAssignments(String raw, String propertyName) {
        raw.split(/\r?\n/)
            .collect { it.trim() }
            .findAll { !it.isEmpty() }
            .collectEntries { entry ->
                def separatorIndex = entry.indexOf('=')
                if (separatorIndex <= 0) {
                    throw new GradleException("Invalid ${propertyName} entry '${entry}'. Expected key=value.")
                }

                [
                    (entry.substring(0, separatorIndex).trim()):
                        entry.substring(separatorIndex + 1).trim()
                ]
            }
    }
}
