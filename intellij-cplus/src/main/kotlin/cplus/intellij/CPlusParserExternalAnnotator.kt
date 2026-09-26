package cplus.intellij

import com.google.gson.JsonParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit

data class CPlusParserDiagnostic(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val warning: Boolean
)

internal object CPlusParserJsonDiagnostics {
    fun decode(json: String): List<CPlusParserDiagnostic> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema")?.asString == "cplus.parse.v1") { "unsupported C-plus parser JSON schema" }
        return root.getAsJsonArray("diagnostics").mapNotNull { element ->
            val diagnostic = element.asJsonObject
            val span = diagnostic.getAsJsonObject("span") ?: return@mapNotNull null
            CPlusParserDiagnostic(
                span.get("startOffset").asInt,
                span.get("endOffset").asInt,
                diagnostic.get("message").asString,
                diagnostic.get("severity").asString == "warning"
            )
        }
    }
}

data class CPlusParserInput(val text: String, val command: String)

class CPlusParserExternalAnnotator : ExternalAnnotator<CPlusParserInput, List<CPlusParserDiagnostic>>() {
    override fun collectInformation(file: PsiFile): CPlusParserInput? {
        if (file.virtualFile == null) return null
        val command = CPlusSettings.getInstance().current().parserCommand.trim()
        if (command.isEmpty()) return null
        return CPlusParserInput(file.text, command)
    }

    override fun doAnnotate(collectedInfo: CPlusParserInput): List<CPlusParserDiagnostic>? {
        val path = Files.createTempFile("cplus-intellij-", ".cp")
        val output = Files.createTempFile("cplus-intellij-", ".json")
        try {
            Files.writeString(path, collectedInfo.text)
            val command = splitCommand(collectedInfo.command) + listOf("parse", path.toString(), "-o", output.toString())
            val process = ProcessBuilder(command).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            val json = Files.readString(output)
            if (json.isBlank()) return emptyList()
            return runCatching { CPlusParserJsonDiagnostics.decode(json) }.getOrNull()
        } catch (_: Exception) {
            return null
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(output)
        }
    }

    override fun apply(file: PsiFile, annotationResult: List<CPlusParserDiagnostic>?, holder: AnnotationHolder) {
        annotationResult.orEmpty().forEach { diagnostic ->
            val start = diagnostic.startOffset.coerceIn(0, file.textLength)
            val end = diagnostic.endOffset.coerceIn(start, file.textLength)
            holder.newAnnotation(
                if (diagnostic.warning) HighlightSeverity.WARNING else HighlightSeverity.ERROR,
                diagnostic.message
            ).range(TextRange(start, end)).create()
        }
    }

    private fun splitCommand(command: String): List<String> =
        Regex("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)")
            .findAll(command)
            .map { match -> match.groupValues.drop(1).first(String::isNotEmpty) }
            .toList()
}
