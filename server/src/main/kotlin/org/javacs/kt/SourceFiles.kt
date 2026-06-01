package org.javacs.kt

import com.intellij.openapi.util.text.StringUtil.convertLineSeparators
import com.intellij.lang.Language
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.filePath
import org.javacs.kt.util.describeURIs
import org.javacs.kt.util.describeURI
import org.jetbrains.kotlin.utils.addToStdlib.butIf
import org.jetbrains.kotlin.utils.addToStdlib.swap
import java.io.IOException
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

private class SourceVersion(val content: String, val version: Int, val language: Language?, val isTemporary: Boolean)

/**
 * Notify SourcePath whenever a file changes
 */
private class NotifySourcePath(private val sp: SourcePath) {
    private val files = mutableMapOf<URI, SourceVersion>()

    operator fun get(uri: URI): SourceVersion? = files[uri]

    operator fun set(uri: URI, source: SourceVersion) {
        val content = convertLineSeparators(source.content)

        files[uri] = source
        sp.put(uri, content, source.language, source.isTemporary)
    }

    fun remove(uri: URI) {
        files.remove(uri)
        sp.delete(uri)
    }

    fun removeIfTemporary(uri: URI): Boolean =
        if (sp.deleteIfTemporary(uri)) {
            files.remove(uri)
            true
        } else {
            false
        }

    fun removeAll(rm: Collection<URI>) {
        files -= rm

        rm.forEach(sp::delete)
    }

    val keys get() = files.keys
}

/**
 * Keep track of the text of all files in the workspace
 */
class SourceFiles(
    private val sp: SourcePath,
    private val contentProvider: URIContentProvider,
    private val scriptsConfig: ScriptsConfiguration
) {
    private val workspaceRoots = mutableSetOf<Path>()
    private var exclusions = SourceExclusions(workspaceRoots, scriptsConfig)
    private val files = NotifySourcePath(sp)
    private val open = mutableSetOf<URI>()

    fun open(uri: URI, content: String, version: Int) {
        if (isIncluded(uri)) {
            files[uri] = SourceVersion(content, version, languageOf(uri), isTemporary = false)
            open.add(uri)
        }
    }

    fun close(uri: URI) {
        if (uri in open) {
            open.remove(uri)
            val removed = files.removeIfTemporary(uri)

            if (!removed) {
                val disk = readFromDisk(uri, temporary = false)

                if (disk != null) {
                    files[uri] = disk
                } else {
                    files.remove(uri)
                }
            }
        }
    }

    fun edit(uri: URI, newVersion: Int, contentChanges: List<TextDocumentContentChangeEvent>) {
        if (isIncluded(uri)) {
            val existing = files[uri]!!
            var newText = existing.content

            if (newVersion <= existing.version) {
                LOG.warn("Ignored {} version {}", describeURI(uri), newVersion)
                return
            }

            for (change in contentChanges) {
                if (change.range == null) newText = change.text
                else newText = patch(newText, change)
            }

            files[uri] = SourceVersion(newText, newVersion, existing.language, existing.isTemporary)
        }
    }

    fun createdOnDisk(uri: URI) {
        changedOnDisk(uri)
    }

    fun deletedOnDisk(uri: URI) {
        if (isSource(uri)) {
            files.remove(uri)
        }
    }

    fun changedOnDisk(uri: URI) {
        if (isSource(uri)) {
            files[uri] = readFromDisk(uri, files[uri]?.isTemporary ?: true)
                ?: throw KotlinLSException("Could not read source file '$uri' after being changed on disk")
        }
    }

    private fun readFromDisk(uri: URI, temporary: Boolean): SourceVersion? = try {
        val content = contentProvider.contentOf(uri)
        SourceVersion(content, -1, languageOf(uri), isTemporary = temporary)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IOException) {
        LOG.warn("Exception while reading source file {}", describeURI(uri))
        null
    }

    private fun isSource(uri: URI): Boolean = isIncluded(uri) && languageOf(uri) != null

    private fun languageOf(uri: URI): Language? {
        val fileName = uri.filePath?.fileName?.toString() ?: return null
        return when {
            fileName.endsWith(".kt") || fileName.endsWith(".kts") -> KotlinLanguage.INSTANCE
            else -> null
        }
    }

    fun addWorkspaceRoot(root: Path) {
        LOG.info("Searching $root using exclusions: ${exclusions.excludedPatterns}")
        val addSources = findSourceFiles(root)

        logAdded(addSources, root)

        for (uri in addSources) {
            readFromDisk(uri, temporary = false)?.let {
                files[uri] = it
            } ?: LOG.warn("Could not read source file '{}'", uri.path)
        }

        workspaceRoots.add(root)
        updateExclusions()
    }

    fun removeWorkspaceRoot(root: Path) {
        val rmSources = files.keys.filter { it.filePath?.startsWith(root) ?: false }

        logRemoved(rmSources, root)

        files.removeAll(rmSources)
        workspaceRoots.remove(root)
        updateExclusions()
    }

    private fun findSourceFiles(root: Path): Set<URI> {
        val sourceMatcher = FileSystems.getDefault().getPathMatcher("glob:*.{kt,kts}")
        return SourceExclusions(listOf(root), scriptsConfig)
            .walkIncluded()
            .filter { sourceMatcher.matches(it.fileName) }
            .map(Path::toUri)
            .toSet()
    }

    fun updateExclusions() {
        exclusions = SourceExclusions(workspaceRoots, scriptsConfig)
        LOG.info("Updated exclusions: ${exclusions.excludedPatterns}")
    }

    fun isOpen(uri: URI): Boolean = (uri in open)

    fun isIncluded(uri: URI): Boolean = exclusions.isURIIncluded(uri)
}

private fun patch(sourceText: String, change: TextDocumentContentChangeEvent): String
    = sourceText.replaceRange(change.range.forString(sourceText), change.text)

private fun Range.forString(string: String): IntRange {
    val (from, to) = (start to end)
        .butIf(start.line > end.line || (start.line == end.line && start.character > end.character)) { it.swap() }
    val fromOffset = string.lineStart(from.line)
    val toOffset = string.lineStart(to.line - from.line, fromOffset)
    return fromOffset + from.character until toOffset + to.character
}

private fun String.lineStart(line: Int, offset: Int = 0): Int {
    var index = offset
    repeat(times = line) {
        while (index < length) {
            val c = get(index++)
            if (c == '\n' || (c == '\r' && (index >= length || get(index) != '\n'))) {
                break
            }
        }
    }
    return index
}

private fun logAdded(sources: Collection<URI>, rootPath: Path?) {
    LOG.info("Adding {} under {} to source path", describeURIs(sources), rootPath)
}

private fun logRemoved(sources: Collection<URI>, rootPath: Path?) {
    LOG.info("Removing {} under {} to source path", describeURIs(sources), rootPath)
}
