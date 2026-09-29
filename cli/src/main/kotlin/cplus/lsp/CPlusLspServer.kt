package cplus.lsp

import cplus.CPlusParseOptions
import cplus.SourceId
import cplus.SourceManager
import cplus.parser.TreeSitterCPlusParserBackend
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** Minimal stdio LSP transport and parser-backed document service. */
class CPlusLspServer(
    input: InputStream = System.`in`,
    output: OutputStream = System.out
) {
    private val input = BufferedInputStream(input)
    private val output = BufferedOutputStream(output)
    private val sources = SourceManager()
    private val parser = TreeSitterCPlusParserBackend()
    private val documents = LinkedHashMap<String, String>()

    fun serve() {
        while (true) {
            val message = readMessage() ?: return
            val request = runCatching { Json.parse(message) as? Json.Object }.getOrNull() ?: continue
            val method = request.string("method") ?: continue
            val id = request.values["id"]
            when (method) {
                "initialize" -> respond(id, "{\"capabilities\":{\"textDocumentSync\":1,\"documentSymbolProvider\":true}}")
                "shutdown" -> {
                    respond(id, "null")
                    return
                }
                "exit" -> return
                "textDocument/didOpen" -> request.documentText()?.let { (uri, text) ->
                    documents[uri] = text
                    publishDiagnostics(uri, text)
                }
                "textDocument/didChange" -> request.documentText()?.let { (uri, text) ->
                    documents[uri] = text
                    publishDiagnostics(uri, text)
                }
                "textDocument/didClose" -> request.uri()?.let { uri ->
                    documents.remove(uri)
                    publishDiagnostics(uri, "")
                }
                "textDocument/documentSymbol" -> respond(id, "[]")
                else -> if (id != null) respondError(id, -32601, "method not supported: $method")
            }
        }
    }

    private fun publishDiagnostics(uri: String, text: String) {
        if (text.isEmpty()) {
            notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[]}")
            return
        }
        val snapshot = sources.open(SourceId.named(uri), text)
        val result = parser.parse(snapshot, CPlusParseOptions(editorMode = true))
        val diagnostics = result.diagnostics.joinToString(",") { diagnostic ->
            val span = diagnostic.span
            "{\"range\":{\"start\":{\"line\":${span.startLine - 1},\"character\":${span.startColumn - 1}}," +
                "\"end\":{\"line\":${span.endLine - 1},\"character\":${span.endColumn - 1}}}," +
                "\"severity\":${if (diagnostic.severity.name == "WARNING") 2 else 1}," +
                "\"code\":${Json.string(diagnostic.code)},\"source\":\"c-plus\",\"message\":${Json.string(diagnostic.message)}}"
        }
        notify("textDocument/publishDiagnostics", "{\"uri\":${Json.string(uri)},\"diagnostics\":[$diagnostics]}")
    }

    private fun notify(method: String, params: String) = write("{\"jsonrpc\":\"2.0\",\"method\":${Json.string(method)},\"params\":$params}")

    private fun respond(id: Json?, result: String) = write("{\"jsonrpc\":\"2.0\",\"id\":${id?.encode() ?: "null"},\"result\":$result}")

    private fun respondError(id: Json, code: Int, message: String) =
        write("{\"jsonrpc\":\"2.0\",\"id\":${id.encode()},\"error\":{\"code\":$code,\"message\":${Json.string(message)}}}")

    private fun write(message: String) {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun readMessage(): String? {
        var contentLength: Int? = null
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull()
            }
        }
        val length = contentLength ?: return null
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) return null
            offset += count
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readHeaderLine(): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

private fun Json.Object.uri(): String? {
    val params = values["params"] as? Json.Object ?: return null
    return (params.values["textDocument"] as? Json.Object)?.string("uri")
}

private fun Json.Object.documentText(): Pair<String, String>? {
    val params = values["params"] as? Json.Object ?: return null
    val document = params.values["textDocument"] as? Json.Object
    val uri = document?.string("uri") ?: return null
    val directText = document.string("text")
    if (directText != null) return uri to directText
    val changes = params.values["contentChanges"] as? Json.Array ?: return null
    val text = (changes.values.firstOrNull() as? Json.Object)?.string("text") ?: return null
    return uri to text
}

private sealed interface Json {
    fun encode(): String

    data class Object(val values: Map<String, Json>) : Json {
        override fun encode() = values.entries.joinToString(",", "{", "}") { (key, value) -> "${string(key)}:${value.encode()}" }
        fun string(key: String): String? = (values[key] as? StringValue)?.value
    }
    data class Array(val values: List<Json>) : Json { override fun encode() = values.joinToString(",", "[", "]", transform = Json::encode) }
    data class StringValue(val value: String) : Json { override fun encode() = string(value) }
    data class NumberValue(val value: String) : Json { override fun encode() = value }
    data class BooleanValue(val value: Boolean) : Json { override fun encode() = value.toString() }
    data object Null : Json { override fun encode() = "null" }

    companion object {
        fun string(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
                }
            }
            append('"')
        }

        fun parse(text: String): Json = Reader(text).parse()

        private class Reader(private val text: String) {
            private var index = 0
            fun parse(): Json { skip(); return value().also { skip(); require(index == text.length) } }
            private fun value(): Json {
                skip()
                return when (text.getOrNull(index)) {
                    '{' -> objectValue()
                    '[' -> arrayValue()
                    '"' -> StringValue(stringValue())
                    't' -> literal("true", BooleanValue(true))
                    'f' -> literal("false", BooleanValue(false))
                    'n' -> literal("null", Null)
                    else -> NumberValue(numberValue())
                }
            }
            private fun objectValue(): Json.Object {
                index++
                val values = linkedMapOf<String, Json>(); skip()
                if (text.getOrNull(index) == '}') { index++; return Json.Object(values) }
                while (true) {
                    val key = stringValue(); skip(); require(text[index++] == ':'); values[key] = value(); skip()
                    if (text[index++] == '}') return Json.Object(values)
                    require(text[index - 1] == ','); skip()
                }
            }
            private fun arrayValue(): Json.Array {
                index++; val values = mutableListOf<Json>(); skip()
                if (text.getOrNull(index) == ']') { index++; return Json.Array(values) }
                while (true) { values += value(); skip(); if (text[index++] == ']') return Json.Array(values); require(text[index - 1] == ','); skip() }
            }
            private fun stringValue(): String {
                require(text[index++] == '"'); val result = StringBuilder()
                while (true) {
                    val character = text[index++]
                    when (character) {
                        '"' -> return result.toString()
                        '\\' -> when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b'); 'f' -> result.append('\u000c'); 'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                            'u' -> result.append(text.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                            else -> error("invalid JSON escape")
                        }
                        else -> result.append(character)
                    }
                }
            }
            private fun numberValue(): String { val start = index; while (text.getOrNull(index)?.let { it == '-' || it == '+' || it == '.' || it.isDigit() || it in "eE" } == true) index++; return text.substring(start, index) }
            private fun <T : Json> literal(expected: String, result: T): T { require(text.startsWith(expected, index)); index += expected.length; return result }
            private fun skip() { while (text.getOrNull(index)?.isWhitespace() == true) index++ }
        }
    }
}
