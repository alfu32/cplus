package cplus.intellij

import com.google.gson.JsonParser
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.ide.structureView.StructureViewFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

data class CPlusParserDiagnostic(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val warning: Boolean
)

data class CPlusParserSymbol(
    val name: String,
    val kind: String,
    val detail: String,
    val startOffset: Int,
    val endOffset: Int,
    val owner: String? = null,
    val isStatic: Boolean = false,
    val children: List<CPlusParserSymbol> = emptyList()
)

data class CPlusParserFixture(val name: String, val startOffset: Int, val endOffset: Int)

data class CPlusParserResult(
    val diagnostics: List<CPlusParserDiagnostic>,
    val symbols: List<CPlusParserSymbol>,
    val fixtures: List<CPlusParserFixture>,
    val sourcePath: String,
    val sourceText: String
)

internal object CPlusParserTreeCache {
    private data class Entry(
        val sourceText: String,
        val symbols: List<CPlusParserSymbol>,
        val fixtures: List<CPlusParserFixture>
    )
    private const val MAX_SOURCE_CHARS = 4 * 1024 * 1024
    private val entries = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 128
    }

    @Synchronized
    fun store(
        path: String,
        sourceText: String,
        symbols: List<CPlusParserSymbol>,
        fixtures: List<CPlusParserFixture> = emptyList()
    ): Boolean {
        if (sourceText.length > MAX_SOURCE_CHARS) {
            return entries.remove(path) != null
        }
        val replacement = Entry(sourceText, symbols, fixtures)
        val previous = entries.put(path, replacement)
        var totalChars = entries.values.sumOf { it.sourceText.length }
        while (totalChars > MAX_SOURCE_CHARS && entries.isNotEmpty()) {
            val eldestPath = entries.entries.iterator().next().key
            totalChars -= entries.remove(eldestPath)?.sourceText?.length ?: 0
        }
        return previous != replacement
    }

    @Synchronized
    fun symbols(path: String, sourceText: String): List<CPlusParserSymbol>? =
        entries[path]?.takeIf { it.sourceText == sourceText }?.symbols

    @Synchronized
    fun fixtures(path: String, sourceText: String): List<CPlusParserFixture>? =
        entries[path]?.takeIf { it.sourceText == sourceText }?.fixtures
}

internal object CPlusParserSymbols {
    fun members(symbols: List<CPlusParserSymbol>, type: String, isStatic: Boolean): List<CPlusParserSymbol> =
        symbols.firstOrNull { it.kind == "type" && it.name == type }
            ?.children.orEmpty()
            .filter { member ->
                when (member.kind) {
                    "field" -> !isStatic
                    "method" -> member.isStatic == isStatic
                    else -> false
                }
            }

    fun declaration(symbols: List<CPlusParserSymbol>, name: String, referenceOffset: Int): CPlusParserSymbol? {
        fun flatten(entries: List<CPlusParserSymbol>): Sequence<CPlusParserSymbol> =
            entries.asSequence().flatMap { sequenceOf(it) + flatten(it.children) }

        return flatten(symbols).firstOrNull {
            it.name == name && referenceOffset !in it.startOffset until it.endOffset
        }
    }
}

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

    fun decodeSymbols(json: String, source: String): List<CPlusParserSymbol> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema")?.asString == "cplus.parse.v1") { "unsupported C-plus parser JSON schema" }
        val ast = root.getAsJsonObject("ast") ?: return emptyList()
        val symbols = mutableListOf<CPlusParserSymbol>()

        fun children(node: com.google.gson.JsonObject): List<com.google.gson.JsonObject> =
            node.getAsJsonArray("children")?.map { it.asJsonObject }.orEmpty()

        fun descendants(node: com.google.gson.JsonObject): Sequence<com.google.gson.JsonObject> =
            children(node).asSequence().flatMap { sequenceOf(it) + descendants(it) }

        fun kind(node: com.google.gson.JsonObject): String = node.get("kind")?.asString.orEmpty()
        fun field(node: com.google.gson.JsonObject): String? = node.get("field")?.takeUnless { it.isJsonNull }?.asString
        fun offset(node: com.google.gson.JsonObject, name: String): Int =
            node.getAsJsonObject("span")?.get(name)?.asInt ?: 0
        fun text(node: com.google.gson.JsonObject): String {
            val start = offset(node, "startOffset").coerceIn(0, source.length)
            val end = offset(node, "endOffset").coerceIn(start, source.length)
            return source.substring(start, end).trim()
        }
        fun declaratorName(node: com.google.gson.JsonObject): com.google.gson.JsonObject? =
            descendants(node).firstOrNull { kind(it) == "identifier" && field(it) == "declarator" }
        fun name(node: com.google.gson.JsonObject?): String? = node?.let(::text)?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }

        for (declaration in children(ast)) {
            if (kind(declaration) == "type_alias") {
                val structure = children(declaration).firstOrNull { kind(it) == "struct_declaration" } ?: continue
                val aliasNode = children(declaration).firstOrNull { kind(it) == "identifier" && field(it) == "declarator" }
                val typeName = name(aliasNode)
                    ?: name(descendants(structure).firstOrNull { kind(it) == "identifier" && field(it) == "name" })
                    ?: "anonymous_struct"
                val members = mutableListOf<CPlusParserSymbol>()
                val body = children(structure).firstOrNull { field(it) == "body" }
                for (member in body?.let(::children).orEmpty()) {
                    val memberKind = kind(member)
                    if (memberKind != "field_declaration" && memberKind != "method_declaration") continue
                    val nameNode = declaratorName(member) ?: continue
                    val memberName = name(nameNode) ?: continue
                    val isMethod = memberKind == "method_declaration"
                    members += CPlusParserSymbol(
                        memberName,
                        if (isMethod) "method" else "field",
                        text(member),
                        offset(nameNode, "startOffset"),
                        offset(nameNode, "endOffset"),
                        owner = typeName,
                        isStatic = isMethod && descendants(member).any { it.get("syntaxKind")?.asString == "cplus_static_modifier" }
                    )
                }
                symbols += CPlusParserSymbol(
                    typeName, "type", "struct $typeName",
                    offset(declaration, "startOffset"), offset(declaration, "endOffset"),
                    children = members
                )
            } else if (kind(declaration) == "function_declaration") {
                val nameNode = declaratorName(declaration) ?: continue
                val functionName = name(nameNode) ?: continue
                symbols += CPlusParserSymbol(
                    functionName, "function", text(declaration),
                    offset(nameNode, "startOffset"), offset(nameNode, "endOffset")
                )
            }
        }
        return symbols
    }

    fun decodeFixtures(json: String, source: String): List<CPlusParserFixture> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("schema")?.asString == "cplus.parse.v1") { "unsupported C-plus parser JSON schema" }
        val ast = root.getAsJsonObject("ast") ?: return emptyList()
        val fixtures = mutableListOf<CPlusParserFixture>()

        fun visit(node: com.google.gson.JsonObject) {
            if (node.get("syntaxKind")?.asString == "cplus_test_declaration") {
                val children = node.getAsJsonArray("children")?.map { it.asJsonObject }.orEmpty()
                val nameNode = children.firstOrNull {
                    it.get("syntaxKind")?.asString in setOf("identifier", "string_literal")
                }
                val span = node.getAsJsonObject("span")
                val nameSpan = nameNode?.getAsJsonObject("span")
                val start = span?.get("startOffset")?.asInt ?: 0
                val end = span?.get("endOffset")?.asInt ?: start
                val name = if (nameSpan == null) "" else {
                    val nameStart = nameSpan.get("startOffset").asInt.coerceIn(0, source.length)
                    val nameEnd = nameSpan.get("endOffset").asInt.coerceIn(nameStart, source.length)
                    val raw = source.substring(nameStart, nameEnd)
                    if (nameNode.get("syntaxKind")?.asString == "string_literal") decodeCString(raw) else raw
                }
                if (name.isNotBlank() && start <= end) fixtures += CPlusParserFixture(name, start, end)
            }
            node.getAsJsonArray("children")?.forEach { visit(it.asJsonObject) }
        }
        visit(ast)
        return fixtures
    }

    private fun decodeCString(literal: String): String {
        if (literal.length < 2 || literal.first() != '"' || literal.last() != '"') return literal
        val body = literal.substring(1, literal.length - 1)
        val result = StringBuilder(body.length)
        var index = 0
        while (index < body.length) {
            val character = body[index++]
            if (character != '\\' || index >= body.length) {
                result.append(character)
                continue
            }
            when (val escaped = body[index++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> result.append(escaped)
            }
        }
        return result.toString()
    }
}

data class CPlusParserInput(val text: String, val command: String, val sourcePath: String)

class CPlusParserExternalAnnotator : ExternalAnnotator<CPlusParserInput, CPlusParserResult>() {
    override fun collectInformation(file: PsiFile): CPlusParserInput? {
        if (file.virtualFile == null) return null
        val command = CPlusSettings.getInstance().current().parserCommand.trim()
        if (command.isEmpty()) return null
        return CPlusParserInput(file.text, command, file.virtualFile.path)
    }

    override fun doAnnotate(collectedInfo: CPlusParserInput): CPlusParserResult? {
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
            if (json.isBlank()) return CPlusParserResult(emptyList(), emptyList(), emptyList(), collectedInfo.sourcePath, collectedInfo.text)
            return runCatching {
                CPlusParserResult(
                    CPlusParserJsonDiagnostics.decode(json),
                    CPlusParserJsonDiagnostics.decodeSymbols(json, collectedInfo.text),
                    CPlusParserJsonDiagnostics.decodeFixtures(json, collectedInfo.text),
                    collectedInfo.sourcePath,
                    collectedInfo.text
                )
            }.getOrNull()
        } catch (_: Exception) {
            return null
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(output)
        }
    }

    override fun apply(file: PsiFile, annotationResult: CPlusParserResult?, holder: AnnotationHolder) {
        if (annotationResult != null && annotationResult.sourceText == file.text) {
            if (CPlusParserTreeCache.store(
                    annotationResult.sourcePath,
                    annotationResult.sourceText,
                    annotationResult.symbols,
                    annotationResult.fixtures
                )) {
                StructureViewFactory.getInstance(file.project).refreshStructureView()
            }
        }
        annotationResult?.diagnostics.orEmpty().forEach { diagnostic ->
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
