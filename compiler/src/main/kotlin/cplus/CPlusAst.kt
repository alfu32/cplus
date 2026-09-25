package cplus

/** Stable compiler-facing node categories; parser-generator names stay in [syntaxKind]. */
enum class CPlusAstKind {
    TRANSLATION_UNIT,
    STRUCT_DECLARATION,
    FIELD_DECLARATION,
    METHOD_DECLARATION,
    FUNCTION_DECLARATION,
    COMPTIME_DECLARATION,
    IMPORT,
    TEST,
    TRY,
    CATCH,
    THROWS_ANNOTATION,
    PARAMETER,
    BLOCK,
    DEFER,
    CALL_EXPRESSION,
    FIELD_ACCESS,
    EXPRESSION,
    STATEMENT,
    ERROR,
    OTHER
}

data class CPlusAstNode(
    val kind: CPlusAstKind,
    val syntaxKind: String,
    val span: SourceSpan,
    val fieldName: String?,
    val children: List<CPlusAstNode>,
    val named: Boolean,
    val opaque: Boolean,
    val recovered: Boolean
)

data class CPlusAst(
    val source: SourceSnapshot,
    val root: CPlusAstNode,
    val diagnostics: List<ParserDiagnostic>,
    val structurallyComplete: Boolean
)

/** Normalizes parser-neutral CST nodes into a stable, lossless compiler tree. */
class CPlusAstAdapter {
    fun adapt(parsed: CPlusParseResult): CPlusAst = CPlusAst(
        source = parsed.source,
        root = parsed.root.toAstNode(),
        diagnostics = parsed.diagnostics,
        structurallyComplete = parsed.coverage == ParseCoverage.STRUCTURAL && parsed.diagnostics.isEmpty()
    )

    private fun CPlusSyntaxNode.toAstNode(): CPlusAstNode = CPlusAstNode(
        kind = categoryFor(kind, isError, isMissing),
        syntaxKind = kind,
        span = span,
        fieldName = fieldName,
        children = children.map { it.toAstNode() },
        named = named,
        opaque = opaque,
        recovered = isError || isMissing
    )

    private fun categoryFor(syntaxKind: String, isError: Boolean, isMissing: Boolean): CPlusAstKind {
        if (isError || isMissing) return CPlusAstKind.ERROR
        return when (syntaxKind) {
            "translation_unit" -> CPlusAstKind.TRANSLATION_UNIT
            "struct_specifier", "union_specifier" -> CPlusAstKind.STRUCT_DECLARATION
            "field_declaration" -> CPlusAstKind.FIELD_DECLARATION
            "cplus_method_definition" -> CPlusAstKind.METHOD_DECLARATION
            "function_definition" -> CPlusAstKind.FUNCTION_DECLARATION
            "cplus_function_declaration" -> CPlusAstKind.FUNCTION_DECLARATION
            "cplus_comptime_declaration", "cplus_comptime_block" -> CPlusAstKind.COMPTIME_DECLARATION
            "cplus_at_import", "cplus_comptime_import" -> CPlusAstKind.IMPORT
            "cplus_test_declaration" -> CPlusAstKind.TEST
            "cplus_try_statement" -> CPlusAstKind.TRY
            "cplus_catch_clause" -> CPlusAstKind.CATCH
            "cplus_throws_annotation" -> CPlusAstKind.THROWS_ANNOTATION
            "parameter_declaration", "cplus_parameter_declaration" -> CPlusAstKind.PARAMETER
            "compound_statement" -> CPlusAstKind.BLOCK
            "cplus_defer_statement" -> CPlusAstKind.DEFER
            "call_expression" -> CPlusAstKind.CALL_EXPRESSION
            "field_expression" -> CPlusAstKind.FIELD_ACCESS
            "expression_statement", "return_statement", "if_statement", "for_statement",
            "while_statement", "do_statement", "switch_statement", "break_statement",
            "continue_statement", "declaration" -> CPlusAstKind.STATEMENT
            "binary_expression", "assignment_expression", "conditional_expression",
            "unary_expression", "update_expression", "cast_expression", "identifier",
            "field_identifier", "number_literal", "string_literal", "char_literal" -> CPlusAstKind.EXPRESSION
            else -> CPlusAstKind.OTHER
        }
    }
}

/** Deterministic, source-spanned tree text for focused parser/adapter golden tests. */
fun CPlusAst.dump(): String = buildString {
    fun appendNode(node: CPlusAstNode, depth: Int) {
        append("  ".repeat(depth))
        append(node.kind.name.lowercase())
        append(" <").append(node.syntaxKind).append('>')
        node.fieldName?.let { append(" field=").append(it) }
        append(" @").append(node.span.startLine).append(':').append(node.span.startColumn)
        append('-').append(node.span.endLine).append(':').append(node.span.endColumn)
        if (!node.named) append(" [anonymous]")
        if (node.opaque) append(" [opaque]")
        if (node.recovered) append(" [recovered]")
        append('\n')
        node.children.forEach { appendNode(it, depth + 1) }
    }
    appendNode(root, 0)
}
