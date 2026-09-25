package cplus

/** Stable compiler-facing node categories; parser-generator names stay in [syntaxKind]. */
enum class CPlusAstKind {
    TRANSLATION_UNIT,
    STRUCT_DECLARATION,
    UNION_DECLARATION,
    ENUM_DECLARATION,
    TYPE_ALIAS,
    FIELD_DECLARATION,
    VARIABLE_DECLARATION,
    METHOD_DECLARATION,
    FUNCTION_DECLARATION,
    COMPTIME_DECLARATION,
    IMPORT,
    TEST,
    TEST_ASSERTION,
    COMPTIME_INVOCATION,
    COMPTIME_EXPRESSION,
    CODE_FRAGMENT,
    INTERPOLATED_IDENTIFIER,
    TRY,
    CATCH,
    THROWS_ANNOTATION,
    PARAMETER,
    BLOCK,
    DEFER,
    CONTROL_FLOW,
    PREPROCESSOR,
    CALL_EXPRESSION,
    FIELD_ACCESS,
    TYPE,
    IDENTIFIER,
    LITERAL,
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

    private fun CPlusSyntaxNode.toAstNode(): CPlusAstNode {
        val adaptedChildren = children.map { it.toAstNode() }
        return CPlusAstNode(
            kind = categoryFor(kind, isError, isMissing, adaptedChildren),
            syntaxKind = kind,
            span = span,
            fieldName = fieldName,
            children = adaptedChildren,
            named = named,
            opaque = opaque,
            recovered = isError || isMissing
        )
    }

    private fun categoryFor(
        syntaxKind: String,
        isError: Boolean,
        isMissing: Boolean,
        children: List<CPlusAstNode>
    ): CPlusAstKind {
        if (isError || isMissing) return CPlusAstKind.ERROR
        return when (syntaxKind) {
            "translation_unit" -> CPlusAstKind.TRANSLATION_UNIT
            "struct_specifier" -> CPlusAstKind.STRUCT_DECLARATION
            "union_specifier" -> CPlusAstKind.UNION_DECLARATION
            "enum_specifier" -> CPlusAstKind.ENUM_DECLARATION
            "type_definition" -> CPlusAstKind.TYPE_ALIAS
            "field_declaration" -> CPlusAstKind.FIELD_DECLARATION
            "cplus_method_definition", "cplus_throws_annotated_method" -> CPlusAstKind.METHOD_DECLARATION
            "function_definition" -> CPlusAstKind.FUNCTION_DECLARATION
            "cplus_function_declaration" -> CPlusAstKind.FUNCTION_DECLARATION
            "declaration" -> if (children.any { it.containsSyntax("function_declarator") }) {
                CPlusAstKind.FUNCTION_DECLARATION
            } else {
                CPlusAstKind.VARIABLE_DECLARATION
            }
            "cplus_comptime_declaration", "cplus_comptime_function_definition", "cplus_comptime_value",
            "cplus_comptime_flags", "cplus_comptime_block", "cplus_comptime_conditional",
            "cplus_legacy_type_generator" -> CPlusAstKind.COMPTIME_DECLARATION
            "cplus_comptime_invocation" -> CPlusAstKind.COMPTIME_INVOCATION
            "cplus_comptime_expression" -> CPlusAstKind.COMPTIME_EXPRESSION
            "cplus_code_fragment" -> CPlusAstKind.CODE_FRAGMENT
            "cplus_interpolated_identifier" -> CPlusAstKind.INTERPOLATED_IDENTIFIER
            "cplus_at_import", "cplus_comptime_import" -> CPlusAstKind.IMPORT
            "cplus_test_declaration" -> CPlusAstKind.TEST
            "cplus_test_assertion_statement" -> CPlusAstKind.TEST_ASSERTION
            "cplus_try_statement" -> CPlusAstKind.TRY
            "cplus_catch_clause" -> CPlusAstKind.CATCH
            "cplus_throws_annotation" -> CPlusAstKind.THROWS_ANNOTATION
            "parameter_declaration", "cplus_parameter_declaration" -> CPlusAstKind.PARAMETER
            "compound_statement" -> CPlusAstKind.BLOCK
            "cplus_defer_statement" -> CPlusAstKind.DEFER
            "call_expression", "cplus_at_call_expression" -> CPlusAstKind.CALL_EXPRESSION
            "field_expression" -> CPlusAstKind.FIELD_ACCESS
            "if_statement", "for_statement", "while_statement", "do_statement", "switch_statement",
            "case_statement", "labeled_statement", "break_statement", "continue_statement",
            "goto_statement" -> CPlusAstKind.CONTROL_FLOW
            "preproc_include", "preproc_def", "preproc_function_def", "preproc_call",
            "preproc_if", "preproc_ifdef", "preproc_else", "preproc_elif", "preproc_elifdef",
            "preproc_elifndef", "preproc_endif" -> CPlusAstKind.PREPROCESSOR
            "expression_statement", "return_statement", "empty_statement" -> CPlusAstKind.STATEMENT
            "binary_expression", "assignment_expression", "conditional_expression",
            "unary_expression", "update_expression", "cast_expression", "comma_expression",
            "parenthesized_expression", "sizeof_expression", "alignof_expression" -> CPlusAstKind.EXPRESSION
            "identifier", "field_identifier", "type_identifier" -> CPlusAstKind.IDENTIFIER
            "primitive_type", "sized_type_specifier", "type_qualifier", "storage_class_specifier",
            "type_qualifier_list", "type_descriptor" -> CPlusAstKind.TYPE
            "number_literal", "string_literal", "char_literal", "true", "false", "null" -> CPlusAstKind.LITERAL
            else -> CPlusAstKind.OTHER
        }
    }

    private fun CPlusAstNode.containsSyntax(expected: String): Boolean =
        syntaxKind == expected || children.any { it.containsSyntax(expected) }
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
