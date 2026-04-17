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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList

import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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
    abstract MapProperty<String, String> getVmArgAssignments()

    @Input
    abstract ListProperty<String> getVmArgsLines()

    @TaskAction
    void patchIni() {
        IniPatcher.patchIniFile(
            iniFile.get().asFile,
            openFileLines.get(),
            optionValueAssignments.get(),
            vmArgAssignments.get(),
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

abstract class PatchInfoPlistTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getPlistFile()

    @Input
    abstract MapProperty<String, String> getStringAssignments()

    @TaskAction
    void patchPlist() {
        InfoPlistPatcher.patchInfoPlist(plistFile.get().asFile, stringAssignments.get())
    }
}

final class IniPatcher {
    static void patchIniFile(
        File iniFile,
        List<String> openFileLines,
        Map<String, String> optionValueAssignments,
        Map<String, String> vmArgAssignments,
        List<String> vmArgsLines
    ) {
        if (!iniFile.exists()) {
            throw new GradleException("Cannot patch missing ini file: ${iniFile}")
        }

        def updatedLines = iniFile.readLines('UTF-8')

        applyOptionValueAssignments(updatedLines, optionValueAssignments)
        insertLinesAfterLauncherDefaultAction(updatedLines, 'openFile', openFileLines)
        applyVmArgAssignments(updatedLines, vmArgAssignments)
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

    private static void insertLinesAfterLauncherDefaultAction(List<String> lines, String actionValue, List<String> additions) {
        def normalizedAdditions = additions.collect { it.trim() }.findAll { !it.isEmpty() }
        if (normalizedAdditions.isEmpty()) {
            return
        }

        def insertionIndex = findInsertionIndexAfterLauncherDefaultAction(lines, actionValue)
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

    private static void applyVmArgAssignments(List<String> lines, Map<String, String> assignments) {
        if (assignments.isEmpty()) {
            return
        }

        def vmArgsIndex = lines.indexOf('-vmargs')
        def insertionIndex = vmArgsIndex >= 0 ? vmArgsIndex + 1 : insertionIndexBeforeVmArgs(lines)

        assignments.each { rawKey, rawValue ->
            def key = rawKey?.trim()
            def value = rawValue?.trim()

            if (!key || value == null || value.isEmpty()) {
                return
            }

            def replacementLine = "${key}=${value}"
            def existingIndex = lines.findIndexOf(insertionIndex) { line ->
                line?.trim()?.startsWith("${key}=")
            }

            if (existingIndex >= 0) {
                lines[existingIndex] = replacementLine
            } else {
                lines.add(insertionIndex, replacementLine)
            }
        }
    }

    private static int findInsertionIndexAfterLauncherDefaultAction(List<String> lines, String actionValue) {
        def optionIndex = lines.indexOf('--launcher.defaultAction')
        if (optionIndex < 0) {
            optionIndex = lines.indexOf('-openFile')
        }

        if (optionIndex < 0) {
            return insertionIndexBeforeVmArgs(lines)
        }

        def insertionIndex = optionIndex + 1
        if (insertionIndex < lines.size() && lines[insertionIndex].trim() == actionValue) {
            insertionIndex++
        } else if (insertionIndex < lines.size() && !isOptionLine(lines[insertionIndex])) {
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

final class InfoPlistPatcher {
    static void patchInfoPlist(File plistFile, Map<String, String> stringAssignments) {
        if (!plistFile.exists()) {
            throw new GradleException("Cannot patch missing Info.plist: ${plistFile}")
        }

        def normalizedAssignments = stringAssignments.collectEntries { key, value ->
            def normalizedKey = key?.trim()
            def normalizedValue = value?.trim()
            if (!normalizedKey || normalizedValue == null || normalizedValue.isEmpty()) {
                return [:]
            }

            [(normalizedKey): normalizedValue]
        }

        if (normalizedAssignments.isEmpty()) {
            return
        }

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(plistFile)
        document.documentElement.normalize()

        Element plist = document.documentElement
        Element dict = firstChildElementByName(plist, 'dict')
        if (dict == null) {
            throw new GradleException("Invalid Info.plist: missing root dict in ${plistFile}")
        }

        normalizedAssignments.each { key, value ->
            upsertStringValue(document, dict, key, value)
        }

        def transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, 'yes')
        transformer.setOutputProperty(OutputKeys.ENCODING, 'UTF-8')
        transformer.setOutputProperty('{http://xml.apache.org/xslt}indent-amount', '4')
        transformer.transform(new DOMSource(document), new StreamResult(plistFile))
    }

    private static void upsertStringValue(Document document, Element dict, String key, String value) {
        Element keyElement = findKeyElement(dict, key)
        if (keyElement == null) {
            dict.appendChild(document.createTextNode('\n    '))
            keyElement = document.createElement('key')
            keyElement.setTextContent(key)
            dict.appendChild(keyElement)

            dict.appendChild(document.createTextNode('\n    '))
            def valueElement = document.createElement('string')
            valueElement.setTextContent(value)
            dict.appendChild(valueElement)
            dict.appendChild(document.createTextNode('\n'))
            return
        }

        Node valueNode = nextElementSibling(keyElement)
        if (valueNode instanceof Element && valueNode.tagName == 'string') {
            valueNode.setTextContent(value)
            return
        }

        def replacement = document.createElement('string')
        replacement.setTextContent(value)

        if (valueNode != null) {
            dict.replaceChild(replacement, valueNode)
        } else {
            dict.appendChild(document.createTextNode('\n    '))
            dict.appendChild(replacement)
            dict.appendChild(document.createTextNode('\n'))
        }
    }

    private static Element findKeyElement(Element dict, String keyName) {
        NodeList childNodes = dict.childNodes
        for (int i = 0; i < childNodes.length; i++) {
            Node child = childNodes.item(i)
            if (child instanceof Element && child.tagName == 'key' && child.textContent == keyName) {
                return (Element) child
            }
        }
        return null
    }

    private static Element firstChildElementByName(Element parent, String tagName) {
        NodeList childNodes = parent.childNodes
        for (int i = 0; i < childNodes.length; i++) {
            Node child = childNodes.item(i)
            if (child instanceof Element && child.tagName == tagName) {
                return (Element) child
            }
        }
        return null
    }

    private static Node nextElementSibling(Node node) {
        Node current = node?.nextSibling
        while (current != null) {
            if (current instanceof Element) {
                return current
            }
            current = current.nextSibling
        }
        return null
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
