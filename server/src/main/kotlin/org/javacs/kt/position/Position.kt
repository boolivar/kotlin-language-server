package org.javacs.kt.position

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.javacs.kt.LOG
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.descriptors.SourceFile
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.resolve.source.PsiSourceFile
import kotlin.math.max

fun extractRange(content: String, range: Range) =
    content.substring(
        offset(content, range.start),
        offset(content, range.end)
    )

fun offset(content: String, position: Position): Int
    = position.toOffset(content)

/**
 * Convert from 0-based line and column to 0-based offset
 */
fun offset(content: String, line: Int, char: Int): Int {
    var offset = 0
    var lineOffset = 0

    while (lineOffset < line) {
        if (offset > content.length) {
            throw RuntimeException("Reached end of file before reaching line $line")
        }

        if (content[offset] == '\n') {
            lineOffset++
        }

        offset++
    }
    return (offset + max(0, char)).takeIf { it < content.length }
        ?: throw RuntimeException("Reached end of file before reaching char $char")
}

fun position(content: String, offset: Int): Position {
    if (offset > content.length) {
        throw RuntimeException("Reached end of file before reaching offset $offset")
    }

    var line = 0
    var char = 0
    var find = 0
    while (find < offset) {
        char++
        if (content[find] == '\n') {
            line++
            char = 0
        }
        find++
    }

    return Position(line, char)
}

fun range(content: String, range: TextRange) =
        Range(position(content, range.startOffset), position(content, range.endOffset))

fun location(declaration: DeclarationDescriptor): Location? {
    val psiLocation = declaration.findPsi()?.let(::location)
    if (psiLocation != null) return psiLocation

    if (declaration is DeclarationDescriptorWithSource) {
        val sourceFile = declaration.source.containingFile
        when (sourceFile) {
            is PsiSourceFile -> {
                val file = sourceFile.psiFile.toURIString()
                return Location(file, Range(Position(0, 0), Position(0, 0)))
            }
            SourceFile.NO_SOURCE_FILE -> Unit // If no source file is present, do nothing
            else -> LOG.info("Source type of {} not recognized", sourceFile)
        }
    } else {
        LOG.info("{} does not have a source", declaration)
    }

    return null
}

val Position.isZero: Boolean
    get() = (line == 0) && (character == 0)

val Range.isZero: Boolean
    get() = start.isZero && end.isZero

fun Position.toOffset(content: String): Int
    = offset(content, line, character)

operator fun Position.compareTo(other: Position): Int {
    if (line == other.line && character == other.character) {
        return 0
    }
    if (line < other.line || (line == other.line && character < other.character)) {
        return -1
    }
    return 1
}

fun location(expr: PsiElement): Location? {
    val content = try { expr.containingFile?.text } catch (e: NullPointerException) { null }
    val file = expr.containingFile.toURIString()
    return content?.let { Location(file, range(it, expr.textRange)) }
}

fun PsiFile.toURIString() = toPath().toUri().toString()

/**
 * Region that has been changed
 */
fun changedRegion(oldContent: String, newContent: String): Pair<TextRange, TextRange>? {
    if (oldContent == newContent) return null

    val prefix = oldContent.commonPrefixWith(newContent).length
    val suffix = oldContent.commonSuffixWith(newContent).length
    val oldEnd = max(oldContent.length - suffix, prefix)
    val newEnd = max(newContent.length - suffix, prefix)

    return Pair(TextRange(prefix, oldEnd), TextRange(prefix, newEnd))
}
