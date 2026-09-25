package cplus

data class CPlusExtractedTestFixture(
    val name: String,
    val body: MappedText,
    val span: SourceSpan
)

data class CPlusTestExtractionResult(
    val source: MappedText,
    val fixtures: List<CPlusExtractedTestFixture>,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** AST-bounded extraction of named @test blocks; test bodies remain mapped C-plus source. */
class CPlusTestExtractionPass {
    fun extract(ast: CPlusAst, source: MappedText): CPlusTestExtractionResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val tests = ast.root.testNodes().filter { it.syntaxKind == "cplus_test_declaration" }.toList()
        val fixtures = mutableListOf<CPlusExtractedTestFixture>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        for (test in tests) {
            val nameNode = test.children.firstOrNull { it.syntaxKind == "string_literal" || it.syntaxKind == "identifier" }
            val bodyNode = test.children.firstOrNull { it.syntaxKind == "compound_statement" }
            if (nameNode == null || bodyNode == null) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_TEST_SHAPE", "test declaration must have a name and a compound body", test.span
                )
                continue
            }
            val rawName = source.text.substring(nameNode.span.startOffset, nameNode.span.endOffset)
            val name = if (nameNode.syntaxKind == "string_literal") decodeStringLiteral(rawName) else rawName
            if (name.isBlank()) {
                diagnostics += CPlusLoweringDiagnostic(
                    "CPLUS_TEST_NAME", "test declaration requires a non-empty name", nameNode.span
                )
                continue
            }
            fixtures += CPlusExtractedTestFixture(
                name = name,
                body = source.slice(bodyNode.span.startOffset, bodyNode.span.endOffset),
                span = test.span
            )
        }
        if (diagnostics.isNotEmpty()) return CPlusTestExtractionResult(source, emptyList(), diagnostics)
        val edits = tests.map { test ->
            CPlusMappedEdit(test.span, MappedText.generated("", source.originAt(test.span.startOffset)))
        }
        return CPlusTestExtractionResult(CPlusMappedAstEmitter().emit(ast, source, edits), fixtures, emptyList())
    }

    private fun CPlusAstNode.testNodes(): Sequence<CPlusAstNode> =
        sequence { yield(this@testNodes); children.forEach { yieldAll(it.testNodes()) } }

    private fun decodeStringLiteral(literal: String): String {
        val content = literal.removeSurrounding("\"")
        val result = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val character = content[index++]
            if (character != '\\' || index == content.length) {
                result.append(character)
                continue
            }
            when (val escaped = content[index++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                else -> result.append(escaped)
            }
        }
        return result.toString()
    }
}
