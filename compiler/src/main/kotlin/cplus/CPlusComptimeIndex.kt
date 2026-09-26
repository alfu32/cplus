package cplus

/** A source-spanned comptime construct extracted from parser-produced syntax, not text scanning. */
data class CPlusComptimeConstruct(
    val syntaxKind: String,
    val span: SourceSpan,
    val symbol: String?,
    val bodySpan: SourceSpan?,
    val activeThisPass: Boolean,
    /** Nearest comptime generator whose returned body contains this construct, if any. */
    val enclosingGeneratorSpan: SourceSpan?,
    /** Signature parameters from the syntax tree; nullable names represent unnamed C parameters. */
    val parameters: List<CPlusComptimeParameter> = emptyList(),
    /** Argument expression/type spans in source order, without comma or delimiter tokens. */
    val argumentSpans: List<SourceSpan> = emptyList(),
    val resultKind: String? = null,
    val alias: String? = null
)

data class CPlusComptimeParameter(
    val name: String?,
    val typeText: String?,
    val span: SourceSpan,
    val genericType: Boolean
)

data class CPlusComptimeIndex(
    val constructs: List<CPlusComptimeConstruct>,
    val imports: List<SourceSpan>,
    val tests: List<SourceSpan>
)

/** Indexes comptime declarations/invocations for later evaluator migration. No evaluation occurs. */
class CPlusComptimeIndexer {
    fun index(ast: CPlusAst): CPlusComptimeIndex {
        data class IndexedNode(
            val node: CPlusAstNode,
            val dormant: Boolean,
            val enclosingGeneratorSpan: SourceSpan?
        )

        val nodes = mutableListOf<IndexedNode>()
        fun collect(
            node: CPlusAstNode,
            dormant: Boolean = false,
            enclosingGeneratorSpan: SourceSpan? = null
        ) {
            nodes += IndexedNode(node, dormant, enclosingGeneratorSpan)
            node.children.forEach { child ->
                val bodyOfGenerator = node.syntaxKind in setOf(
                    "cplus_comptime_function_definition",
                    "cplus_legacy_type_generator",
                    "cplus_legacy_function_generator"
                ) &&
                    (child.fieldName == "body" || child.syntaxKind == "compound_statement")
                collect(
                    child,
                    dormant || bodyOfGenerator,
                    if (bodyOfGenerator) node.span else enclosingGeneratorSpan
                )
            }
        }
        collect(ast.root)
        val constructs = nodes.asSequence()
            .filter { it.node.syntaxKind in COMPTIME_NODES }
            .map { indexed ->
                val node = indexed.node
                val symbolNode = when (node.syntaxKind) {
                    "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_legacy_function_generator" ->
                        node.children.firstOrNull { it.fieldName == "name" }
                    "cplus_comptime_invocation" -> node.children.firstOrNull { it.fieldName == "generator" }
                    "cplus_comptime_type_definition" -> node.children.firstOrNull { it.fieldName == "generator" }
                        ?.children?.firstOrNull { it.syntaxKind == "identifier" }
                    "cplus_comptime_value" -> node.children.firstOrNull { it.fieldName == "name" }
                    else -> null
                }
                val body = node.children.firstOrNull { it.fieldName == "body" }
                    ?: node.children.firstOrNull { it.syntaxKind == "compound_statement" }
                val parameters = if (node.syntaxKind in GENERATOR_NODES) {
                    node.children.asSequence()
                        .filter { it.syntaxKind in PARAMETER_NODES }
                        .map { parameter ->
                            val genericType = parameter.syntaxKind in GENERIC_PARAMETER_NODES
                            val declarator = parameter.children.firstOrNull { it.fieldName == "declarator" }
                            val nameNode = parameter.children.firstOrNull { it.fieldName == "name" }
                                ?: (declarator ?: parameter).descendantsAndSelf()
                                    .firstOrNull { it.syntaxKind in setOf("identifier", "type_identifier") }
                            val typeNode = parameter.children.firstOrNull { it.fieldName == "type" }
                            CPlusComptimeParameter(
                                name = nameNode?.text(ast.source.text),
                                typeText = if (genericType) "type" else typeNode?.text(ast.source.text),
                                span = parameter.span,
                                genericType = genericType
                            )
                        }.toList()
                } else emptyList()
                val invocation = when (node.syntaxKind) {
                    "cplus_comptime_invocation" -> node
                    "cplus_comptime_type_definition" -> node.children.firstOrNull { it.fieldName == "generator" }
                    else -> null
                }
                val argumentList = invocation?.descendantsAndSelf()
                    ?.firstOrNull { it.syntaxKind == "argument_list" }
                val argumentSpans = if (argumentList != null) {
                    argumentList.children.filter { it.named && it.syntaxKind != "comment" }.map { it.span }
                } else if (invocation?.syntaxKind == "cplus_comptime_invocation") {
                    invocation.children.filter {
                        it.named && it.fieldName != "generator" && it.fieldName != "alias" && it.syntaxKind != "comment"
                    }.map { it.span }
                } else emptyList()
                CPlusComptimeConstruct(
                    syntaxKind = node.syntaxKind,
                    span = node.span,
                    symbol = symbolNode?.text(ast.source.text)?.removePrefix("@"),
                    bodySpan = body?.span,
                    activeThisPass = !indexed.dormant,
                    enclosingGeneratorSpan = indexed.enclosingGeneratorSpan,
                    parameters = parameters,
                    argumentSpans = argumentSpans,
                    resultKind = node.children.firstOrNull { it.fieldName == "result_kind" }?.text(ast.source.text),
                    alias = node.children.firstOrNull { it.fieldName == "alias" }?.text(ast.source.text)
                )
            }.toList()
        return CPlusComptimeIndex(
            constructs,
            nodes.filter { it.node.syntaxKind in IMPORT_NODES && !it.dormant }.map { it.node.span },
            nodes.filter { it.node.syntaxKind == "cplus_test_declaration" && !it.dormant }.map { it.node.span }
        )
    }

    private fun CPlusAstNode.text(source: String): String = source.substring(span.startOffset, span.endOffset)

    private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }

    private companion object {
        val COMPTIME_NODES = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_legacy_function_generator",
            "cplus_comptime_type_definition", "cplus_comptime_import", "cplus_comptime_flags",
            "cplus_comptime_invocation", "cplus_comptime_value", "cplus_comptime_block",
            "cplus_comptime_conditional"
        )
        val IMPORT_NODES = setOf("cplus_comptime_import", "cplus_at_import")
        val GENERATOR_NODES = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_legacy_function_generator"
        )
        val PARAMETER_NODES = setOf(
            "cplus_generic_type_parameter", "cplus_legacy_generic_type_parameter",
            "parameter_declaration", "cplus_parameter_declaration"
        )
        val GENERIC_PARAMETER_NODES = setOf("cplus_generic_type_parameter", "cplus_legacy_generic_type_parameter")
    }
}
