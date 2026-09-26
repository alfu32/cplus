package cplus

/** Stable compiler-facing node categories; parser-generator names stay in [syntaxKind]. */
enum class CPlusAstKind {
    TRANSLATION_UNIT,
    STRUCT_DECLARATION,
    UNION_DECLARATION,
    ENUM_DECLARATION,
    ENUMERATOR_LIST,
    TYPE_ALIAS,
    DECLARATION_LIST,
    FIELD_LIST,
    LINKAGE_SPECIFICATION,
    FIELD_DECLARATION,
    ENUMERATOR,
    TYPE_PARAMETER,
    ANNOTATION,
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
    PARAMETER_LIST,
    ARGUMENT_LIST,
    BLOCK,
    DEFER,
    CONTROL_FLOW,
    PREPROCESSOR,
    CALL_EXPRESSION,
    FIELD_ACCESS,
    TYPE,
    DECLARATOR,
    INITIALIZER,
    ATTRIBUTE,
    DESIGNATOR,
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
            "enumerator" -> CPlusAstKind.ENUMERATOR
            "enumerator_list" -> CPlusAstKind.ENUMERATOR_LIST
            "type_definition" -> CPlusAstKind.TYPE_ALIAS
            "field_declaration" -> CPlusAstKind.FIELD_DECLARATION
            "field_declaration_list" -> CPlusAstKind.FIELD_LIST
            "declaration_list" -> CPlusAstKind.DECLARATION_LIST
            "init_declarator", "pointer_declarator", "array_declarator", "function_declarator",
            "parenthesized_declarator", "attributed_declarator", "abstract_array_declarator",
            "abstract_function_declarator", "abstract_parenthesized_declarator",
            "abstract_pointer_declarator", "_abstract_declarator", "_declarator",
            "_field_declarator", "_type_declarator", "cplus_method_declarator" -> CPlusAstKind.DECLARATOR
            "initializer_list", "initializer_pair" -> CPlusAstKind.INITIALIZER
            "attribute", "attribute_declaration", "attribute_specifier", "__attribute", "__attribute__" -> CPlusAstKind.ATTRIBUTE
            "field_designator", "subscript_designator", "subscript_range_designator" -> CPlusAstKind.DESIGNATOR
            "bitfield_clause" -> CPlusAstKind.DECLARATOR
            "linkage_specification" -> CPlusAstKind.LINKAGE_SPECIFICATION
            "cplus_method_definition", "cplus_throws_annotated_method" -> CPlusAstKind.METHOD_DECLARATION
            "function_definition" -> CPlusAstKind.FUNCTION_DECLARATION
            "cplus_function_declaration" -> CPlusAstKind.FUNCTION_DECLARATION
            "declaration" -> {
                val declarations = children.filter { it.fieldName == "declarator" }
                if (declarations.isNotEmpty() && declarations.all(::declaresFunction)) {
                    CPlusAstKind.FUNCTION_DECLARATION
                } else {
                    CPlusAstKind.VARIABLE_DECLARATION
                }
            }
            "cplus_comptime_declaration", "cplus_comptime_function_definition", "cplus_comptime_value",
            "cplus_comptime_flags", "cplus_comptime_block", "cplus_comptime_conditional",
            "cplus_legacy_type_generator", "cplus_legacy_function_generator" -> CPlusAstKind.COMPTIME_DECLARATION
            "cplus_comptime_invocation", "cplus_comptime_type_definition" -> CPlusAstKind.COMPTIME_INVOCATION
            "cplus_legacy_returned_function" -> CPlusAstKind.FUNCTION_DECLARATION
            "cplus_comptime_expression" -> CPlusAstKind.COMPTIME_EXPRESSION
            "cplus_code_fragment" -> CPlusAstKind.CODE_FRAGMENT
            "cplus_comptime_body" -> CPlusAstKind.BLOCK
            "cplus_comptime_return_declaration" -> CPlusAstKind.STATEMENT
            "cplus_interpolated_identifier" -> CPlusAstKind.INTERPOLATED_IDENTIFIER
            "cplus_at_import", "cplus_comptime_import" -> CPlusAstKind.IMPORT
            "cplus_test_declaration" -> CPlusAstKind.TEST
            "cplus_test_assertion_statement" -> CPlusAstKind.TEST_ASSERTION
            "cplus_try_statement" -> CPlusAstKind.TRY
            "cplus_catch_clause" -> CPlusAstKind.CATCH
            "cplus_throws_annotation" -> CPlusAstKind.THROWS_ANNOTATION
            "parameter_declaration", "cplus_parameter_declaration", "variadic_parameter" -> CPlusAstKind.PARAMETER
            "cplus_generic_type_parameter", "cplus_legacy_generic_type_parameter" -> CPlusAstKind.TYPE_PARAMETER
            "cplus_access_modifier", "cplus_parameter_annotation", "cplus_result_annotation",
            "cplus_static_modifier" -> CPlusAstKind.ANNOTATION
            "cplus_type_reference" -> CPlusAstKind.TYPE
            "parameter_list" -> CPlusAstKind.PARAMETER_LIST
            "argument_list" -> CPlusAstKind.ARGUMENT_LIST
            "compound_statement" -> CPlusAstKind.BLOCK
            "cplus_defer_statement" -> CPlusAstKind.DEFER
            "call_expression", "cplus_at_call_expression" -> CPlusAstKind.CALL_EXPRESSION
            "field_expression" -> CPlusAstKind.FIELD_ACCESS
            "if_statement", "else_clause", "for_statement", "while_statement", "do_statement", "switch_statement",
            "case_statement", "labeled_statement", "break_statement", "continue_statement",
            "goto_statement", "seh_try_statement", "seh_except_clause", "seh_finally_clause",
            "seh_leave_statement" -> CPlusAstKind.CONTROL_FLOW
            "preproc_include", "preproc_def", "preproc_function_def", "preproc_call",
            "preproc_if", "preproc_ifdef", "preproc_else", "preproc_elif", "preproc_elifdef",
            "preproc_elifndef", "preproc_endif", "preproc_arg", "preproc_params", "preproc_defined",
            "preproc_directive" -> CPlusAstKind.PREPROCESSOR
            "expression_statement", "return_statement", "empty_statement", "statement",
            "attributed_statement" -> CPlusAstKind.STATEMENT
            "expression", "binary_expression", "assignment_expression", "conditional_expression",
            "unary_expression", "update_expression", "cast_expression", "comma_expression",
            "parenthesized_expression", "compound_literal_expression", "extension_expression",
            "generic_expression", "gnu_asm_expression", "offsetof_expression", "pointer_expression",
            "sizeof_expression", "alignof_expression", "subscript_expression", "gnu_asm_clobber_list",
            "gnu_asm_goto_list", "gnu_asm_input_operand", "gnu_asm_input_operand_list",
            "gnu_asm_output_operand", "gnu_asm_output_operand_list", "gnu_asm_qualifier" -> CPlusAstKind.EXPRESSION
            "identifier", "field_identifier", "type_identifier" -> CPlusAstKind.IDENTIFIER
            "primitive_type", "type_specifier", "sized_type_specifier", "type_qualifier", "storage_class_specifier",
            "type_qualifier_list", "type_descriptor", "macro_type_specifier", "ms_pointer_modifier",
            "ms_restrict_modifier", "ms_signed_ptr_modifier", "ms_unsigned_ptr_modifier",
            "ms_unaligned_ptr_modifier" -> CPlusAstKind.TYPE
            "number_literal", "string_literal", "char_literal", "concatenated_string", "character",
            "escape_sequence", "string_content", "system_lib_string", "true", "false", "null" -> CPlusAstKind.LITERAL
            "alignas_qualifier", "ms_based_modifier", "ms_call_modifier", "ms_declspec_modifier" -> CPlusAstKind.ATTRIBUTE
            else -> CPlusAstKind.OTHER
        }
    }

    /**
     * Classifies the entity named by the declarator, not any function layer in its type.
     * `int (*callback)(int)` is an object declaration even though its type contains a function
     * declarator; walking the binding layers from the identifier distinguishes it from `int f()`.
     */
    private fun declaresFunction(declaration: CPlusAstNode): Boolean {
        val declarator = if (declaration.syntaxKind == "init_declarator") {
            declaration.children.firstOrNull { it.fieldName == "declarator" }
        } else {
            declaration
        } ?: return false
        val layers = mutableListOf<String>()
        var current = declarator
        while (true) {
            when (current.syntaxKind) {
                "pointer_declarator" -> layers += "pointer"
                "array_declarator" -> layers += "array"
                "function_declarator" -> layers += "function"
            }
            if (current.syntaxKind in DECLARATOR_IDENTIFIERS) {
                return layers.lastOrNull() == "function"
            }
            current = current.children.firstOrNull {
                it.fieldName == "declarator" && it.syntaxKind in DECLARATOR_KINDS
            } ?: current.children.firstOrNull { it.syntaxKind in DECLARATOR_KINDS }
                ?: return false
        }
    }

    private companion object {
        val DECLARATOR_IDENTIFIERS = setOf("identifier", "field_identifier", "type_identifier")
        val DECLARATOR_KINDS = DECLARATOR_IDENTIFIERS + setOf(
            "pointer_declarator", "array_declarator", "function_declarator", "parenthesized_declarator",
            "attributed_declarator", "cplus_interpolated_identifier"
        )
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
