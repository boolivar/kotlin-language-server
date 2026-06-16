package org.javacs.kt.externalsources

import java.nio.file.Files
import java.nio.file.Path
import org.javacs.kt.LOG
import org.javacs.kt.LogLevel
import org.javacs.kt.log
import org.javacs.kt.util.KotlinLSException
import org.javacs.kt.util.replaceExtensionWith
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences

class FernFlowerDecompiler : Decompiler {

    private val outputDir: Path by lazy {
        Files.createTempDirectory("fernflowerOut").also {
            Runtime.getRuntime().addShutdownHook(Thread { it.toFile().deleteRecursively() })
        }
    }

    private val decompilerOptions =
        mapOf(
            IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH to "1",
            IFernflowerPreferences.USE_JAD_PARAMETER_RENAMING to "1",
        )

    override fun decompileClass(compiledClass: Path): Path {
        return decompile(compiledClass, ".java")
    }

    override fun decompileJar(compiledJar: Path): Path {
        return decompile(compiledJar, ".jar")
    }

    private fun decompile(input: Path, extension: String): Path {
        LOG.info("Decompiling ${input.fileName} using FernFlower...")
        ConsoleDecompiler(outputDir.toFile(), decompilerOptions, FernflowerLogger).apply {
            addSource(input.toFile())
            decompileContext()
        }
        val outName = input.fileName.replaceExtensionWith(extension)
        val outPath = outputDir.resolve(outName)
        if (!Files.exists(outPath)) {
            throw KotlinLSException(
                "Could not decompile ${input.fileName}: FernFlower did not generate sources at $outName"
            )
        }
        return outPath
    }
}

internal object FernflowerLogger: PrintStreamLogger(null) {

    override fun writeMessage(message: String?, t: Throwable) = log(2, LogLevel.ERROR, message, t)

    override fun writeMessage(message: String?, severity: Severity) = log(2, severity.level, message)

    override fun writeMessage(message: String?, severity: Severity, t: Throwable) = log(2, severity.level, message, t)

    private val Severity.level
        get() = when (this) {
            Severity.INFO -> LogLevel.INFO
            Severity.WARN -> LogLevel.WARN
            Severity.ERROR -> LogLevel.ERROR
            else -> LogLevel.DEBUG
        }
}
