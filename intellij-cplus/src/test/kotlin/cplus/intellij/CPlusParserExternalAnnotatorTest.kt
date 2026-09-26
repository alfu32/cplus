package cplus.intellij

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CPlusParserExternalAnnotatorTest {
    @Test
    fun decodesVersionedParserDiagnosticsAndUtf16Offsets() {
        val diagnostics = CPlusParserJsonDiagnostics.decode(
            """{"schema":"cplus.parse.v1","diagnostics":[{"code":"TS_ERROR_NODE","message":"bad syntax","severity":"error","span":{"startOffset":4,"endOffset":7,"startLine":1,"startColumn":5,"endLine":1,"endColumn":8}}]}"""
        )

        assertEquals(listOf(CPlusParserDiagnostic(4, 7, "bad syntax", warning = false)), diagnostics)
    }

    @Test
    fun rejectsUnknownParserJsonSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            CPlusParserJsonDiagnostics.decode("""{"schema":"cplus.parse.v0","diagnostics":[]}""")
        }
    }

    @Test
    fun decodesNormalizedStructMembersAndStaticMethods() {
        val source = "typedef struct sample_t { int value; pub int read(*self); static pub sample_t* create(void); } sample_t; int top(void);"
        val fieldName = source.indexOf("value")
        val readName = source.indexOf("read")
        val createName = source.indexOf("create")
        val aliasName = source.lastIndexOf("sample_t")
        val structStart = source.indexOf("struct sample_t")
        val structEnd = source.indexOf("}", structStart) + 1
        val bodyStart = source.indexOf("{")
        val bodyEnd = structEnd
        val declarationEnd = source.indexOf(';', aliasName) + 1
        val functionName = source.indexOf("top")
        val result = parserJson(
            source,
            node("translation_unit", "translation_unit", null, 0, source.length, listOf(
                node("type_alias", "type_definition", null, 0, declarationEnd, listOf(
                    node("struct_declaration", "struct_specifier", "type", structStart, structEnd, listOf(
                        node("other", "field_declaration_list", "body", bodyStart, bodyEnd, listOf(
                            node("field_declaration", "field_declaration", null, source.indexOf("int value"), source.indexOf(';', fieldName) + 1, listOf(
                                node("identifier", "field_identifier", "declarator", fieldName, fieldName + 5)
                            )),
                            node("method_declaration", "cplus_method_definition", null, source.indexOf("pub int read"), source.indexOf(';', readName) + 1, listOf(
                                node("other", "cplus_method_declarator", "declarator", readName, source.indexOf(')', readName) + 1, listOf(
                                    node("identifier", "identifier", "declarator", readName, readName + 4)
                                ))
                            )),
                            node("method_declaration", "cplus_method_definition", null, source.indexOf("static pub"), source.indexOf(';', createName) + 1, listOf(
                                node("other", "cplus_static_modifier", null, source.indexOf("static"), source.indexOf("static") + 6),
                                node("other", "cplus_method_declarator", "declarator", createName, source.indexOf(')', createName) + 1, listOf(
                                    node("identifier", "identifier", "declarator", createName, createName + 6)
                                ))
                            ))
                        )),
                        node("identifier", "type_identifier", "name", source.indexOf("sample_t"), source.indexOf("sample_t") + 8)
                    )),
                    node("identifier", "type_identifier", "declarator", aliasName, aliasName + 8)
                )),
                node("function_declaration", "declaration", null, functionName, source.length, listOf(
                    node("other", "function_declarator", "declarator", functionName, source.length, listOf(
                        node("identifier", "identifier", "declarator", functionName, functionName + 3)
                    ))
                ))
            ))
        )

        val symbols = CPlusParserJsonDiagnostics.decodeSymbols(result, source)

        assertEquals(listOf("sample_t", "top"), symbols.map { it.name })
        assertEquals(listOf("value", "read", "create"), symbols.first().children.map { it.name })
        assertEquals(listOf("field", "method", "method"), symbols.first().children.map { it.kind })
        assertEquals(listOf(false, false, true), symbols.first().children.map { it.isStatic })
        assertEquals(listOf("value", "read"), CPlusParserSymbols.members(symbols, "sample_t", false).map { it.name })
        assertEquals(listOf("create"), CPlusParserSymbols.members(symbols, "sample_t", true).map { it.name })
    }

    @Test
    fun parserTreeCacheRequiresTheExactEditorSnapshot() {
        val symbol = CPlusParserSymbol("Box", "type", "struct Box", 0, 3)
        CPlusParserTreeCache.store("/project/box.cp", "typedef struct Box {} Box;", listOf(symbol))

        assertEquals(listOf(symbol), CPlusParserTreeCache.symbols("/project/box.cp", "typedef struct Box {} Box;"))
        assertNull(CPlusParserTreeCache.symbols("/project/box.cp", "typedef struct Box {} Changed;"))
    }

    private fun parserJson(source: String, ast: JsonObject): String = JsonObject().apply {
        addProperty("schema", "cplus.parse.v1")
        addProperty("source", "/project/source.cp")
        addProperty("revision", 0)
        addProperty("offsetEncoding", "utf16")
        addProperty("backend", "tree_sitter")
        add("ast", ast)
        add("diagnostics", JsonArray())
    }.toString()

    private fun node(
        kind: String,
        syntaxKind: String,
        field: String?,
        start: Int,
        end: Int,
        children: List<JsonObject> = emptyList()
    ): JsonObject = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("syntaxKind", syntaxKind)
        if (field == null) add("field", null) else addProperty("field", field)
        add("span", JsonObject().apply {
            addProperty("startOffset", start)
            addProperty("endOffset", end)
        })
        add("children", JsonArray().apply { children.forEach(::add) })
    }
}
