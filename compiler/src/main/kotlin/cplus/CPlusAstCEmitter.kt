package cplus

data class CPlusAstCEmissionResult(
    val source: MappedText?,
    val diagnostics: List<CPlusLoweringDiagnostic>,
    val synthesizedDeclarations: List<CPlusSynthesizedDeclaration> = emptyList(),
    val synthesizedNodes: List<CPlusAstNode> = emptyList(),
    val synthesizedSource: MappedText? = null
)

/**
 * Emits C from normalized AST terminals rather than copying the complete source snapshot.
 * C token spelling and origins are retained; block/statement layout and trivia are regenerated,
 * while preprocessor regions remain verbatim because their whitespace can affect macro semantics.
 */
class CPlusAstCEmitter {
    private data class Token(
        val text: String,
        val node: CPlusAstNode,
        val ancestors: List<String>,
        val preprocessor: Boolean = false
    )

    fun emit(ast: CPlusAst, source: MappedText): CPlusAstCEmissionResult {
        require(ast.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val invalid = ast.root.descendantsAndSelf().firstOrNull { it.recovered || it.opaque }
        if (!ast.structurallyComplete || invalid != null) {
            val span = invalid?.span ?: ast.root.span
            return CPlusAstCEmissionResult(
                null,
                listOf(
                    CPlusLoweringDiagnostic(
                        "CPLUS_EMIT_INCOMPLETE_AST",
                        "C generation requires a complete, non-opaque syntax tree",
                        span
                    )
                )
            )
        }
        val unlowered = ast.root.descendantsAndSelf().firstOrNull { node ->
            node.kind in setOf(
                CPlusAstKind.METHOD_DECLARATION,
                CPlusAstKind.COMPTIME_DECLARATION,
                CPlusAstKind.COMPTIME_INVOCATION,
                CPlusAstKind.COMPTIME_EXPRESSION,
                CPlusAstKind.CODE_FRAGMENT,
                CPlusAstKind.INTERPOLATED_IDENTIFIER,
                CPlusAstKind.IMPORT,
                CPlusAstKind.TEST,
                CPlusAstKind.TRY,
                CPlusAstKind.CATCH,
                CPlusAstKind.THROWS_ANNOTATION,
                CPlusAstKind.DEFER
            )
        }
        if (unlowered != null) {
            return CPlusAstCEmissionResult(
                null,
                listOf(
                    CPlusLoweringDiagnostic(
                        "CPLUS_EMIT_UNLOWERED_CONSTRUCT",
                        "C generation cannot emit unlowered C-plus construct '${unlowered.syntaxKind}'",
                        unlowered.span
                    )
                )
            )
        }
        // A parser extension may normalize a future C-plus construct as an
        // otherwise structurally complete `OTHER` node.  Do not pass its
        // source spelling through to the C compiler: that would turn an
        // unsupported frontend construct into an apparently successful, but
        // semantically undefined, emission.  Ordinary C extensions remain
        // host-compiler territory and are therefore intentionally preserved.
        val unsupportedCPlusNode = ast.root.descendantsAndSelf().firstOrNull { node ->
            node.kind == CPlusAstKind.OTHER && node.syntaxKind.startsWith("cplus_")
        }
        if (unsupportedCPlusNode != null) {
            return CPlusAstCEmissionResult(
                null,
                listOf(
                    CPlusLoweringDiagnostic(
                        "CPLUS_EMIT_UNSUPPORTED_NODE",
                        "C generation cannot emit unsupported C-plus syntax '${unsupportedCPlusNode.syntaxKind}'",
                        unsupportedCPlusNode.span
                    )
                )
            )
        }

        val tokens = mutableListOf<Token>()
        fun visit(node: CPlusAstNode, ancestors: List<String>) {
            if (node.kind == CPlusAstKind.PREPROCESSOR) {
                tokens += Token(
                    source.text.substring(node.span.startOffset, node.span.endOffset),
                    node,
                    ancestors,
                    preprocessor = true
                )
                return
            }
            // Tree-sitter exposes quotes, contents, and escape sequences as child nodes.
            // Reformatting those children would change the value of a C literal.
            if (node.syntaxKind in setOf("string_literal", "char_literal", "number_literal")) {
                tokens += Token(
                    source.text.substring(node.span.startOffset, node.span.endOffset),
                    node,
                    ancestors
                )
                return
            }
            val children = node.children
                .filter { it.span.endOffset > it.span.startOffset }
                .sortedWith(compareBy<CPlusAstNode> { it.span.startOffset }.thenBy { it.span.endOffset })
            if (children.isEmpty()) {
                if (node.span.endOffset > node.span.startOffset) {
                    tokens += Token(source.text.substring(node.span.startOffset, node.span.endOffset), node, ancestors)
                }
                return
            }
            children.forEach { child -> visit(child, ancestors + node.syntaxKind) }
        }
        visit(ast.root, emptyList())

        val output = MappedTextBuilder()
        var previous: Token? = null
        var previousPrevious: Token? = null
        var braceDepth = 0
        var parenDepth = 0
        var lineStart = true
        var closedBracePending = false

        fun newline(origin: SourceOrigin?) {
            if (!lineStart) {
                output.appendGenerated("\n", origin)
                lineStart = true
            }
        }

        fun indentation(origin: SourceOrigin?, sourceOffset: Int) {
            if (lineStart && braceDepth > 0) {
                // Keep enough leading columns for diagnostics on source lines moved by lowering
                // (notably methods extracted from a struct). The normalized indentation is a
                // minimum; source indentation wins when it carries useful column provenance.
                val lineStartOffset = source.text.lastIndexOf('\n', (sourceOffset - 1).coerceAtLeast(0)) + 1
                val sourcePrefix = source.text.substring(lineStartOffset, sourceOffset)
                val sourceIndent = if (sourcePrefix.all { it == ' ' || it == '\t' }) {
                    sourcePrefix.sumOf { if (it == '\t') 4 else 1 }
                } else {
                    0
                }
                val columns = maxOf(braceDepth * 4, sourceIndent)
                output.appendGenerated(" ".repeat(columns), origin)
                lineStart = false
            }
        }

        tokens.forEach { token ->
            val start = token.node.span.startOffset
            val origin = source.originAt(start)
            if (token.preprocessor) {
                newline(origin)
                output.append(source, start, token.node.span.endOffset)
                val endsWithNewline = token.text.endsWith('\n')
                if (!endsWithNewline) output.appendGenerated("\n", source.originAt(token.node.span.endOffset - 1))
                lineStart = true
                previous = token
                previousPrevious = null
                closedBracePending = false
            } else {
                val spelling = token.text
                var attachedToClosedBrace = false
                if (closedBracePending) {
                    if (spelling in setOf(";", ",", ")", "]", ".", "->", "else", "while")) {
                        if (spelling !in setOf(";", ",", ")", "]", ".", "->")) {
                            output.appendGenerated(" ", origin)
                            lineStart = false
                        }
                        attachedToClosedBrace = true
                    } else {
                        newline(origin)
                    }
                    closedBracePending = false
                }

                if (spelling == "}") {
                    newline(origin)
                    braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    indentation(origin, start)
                    output.append(source, start, token.node.span.endOffset)
                    lineStart = false
                    closedBracePending = true
                } else {
                    val startsNewLine = lineStart
                    indentation(origin, start)
                    if (!startsNewLine && !attachedToClosedBrace && needsSeparator(
                            previous,
                            previousPrevious,
                            token,
                            spelling.startsWith("//") || spelling.startsWith("/*")
                        )) {
                        output.appendGenerated(" ", origin)
                    }
                    output.append(source, start, token.node.span.endOffset)
                    lineStart = false
                    when (spelling) {
                        "{" -> {
                            braceDepth++
                            newline(source.originAt(token.node.span.endOffset - 1))
                        }
                        ";" -> if (parenDepth == 0) newline(source.originAt(token.node.span.endOffset - 1))
                        else -> if (spelling.startsWith("//")) newline(source.originAt(token.node.span.endOffset - 1))
                    }
                    if (spelling == "(") parenDepth++
                    if (spelling == ")") parenDepth = (parenDepth - 1).coerceAtLeast(0)
                    previousPrevious = previous
                    previous = token
                }
            }
        }
        newline(previous?.let { source.originAt(it.node.span.endOffset - 1) })
        return CPlusAstCEmissionResult(output.build(), emptyList())
    }

    /**
     * Emit an AST that may still contain structured method declarations.
     *
     * The parser callback is deliberately supplied by the parser module: the compiler module
     * owns the normalized AST/emission contract but does not depend on a parser-generator
     * binding. Method lowering therefore remains an explicit, mapped AST revision followed by
     * a fresh parse before terminal C emission.
     */
    fun emit(
        ast: CPlusAst,
        source: MappedText,
        reparse: (MappedText) -> CPlusAst
    ): CPlusAstCEmissionResult {
        val structuredMethods = ast.root.descendantsAndSelf()
            .filter { it.kind == CPlusAstKind.METHOD_DECLARATION }
            .toList()
        if (structuredMethods.isEmpty()) return emit(ast, source)

        val lowered = CPlusStructMethodLoweringPass().lower(ast, source)
        if (lowered.diagnostics.isNotEmpty()) {
            return CPlusAstCEmissionResult(null, lowered.diagnostics)
        }
        val loweredAst = reparse(lowered.source)
        if (loweredAst.diagnostics.isNotEmpty() || !loweredAst.structurallyComplete) {
            val diagnostic = loweredAst.diagnostics.firstOrNull()
                ?: ParserDiagnostic(
                    "CPLUS_EMIT_REPARSE_INCOMPLETE",
                    "structured AST emission requires a complete reparsed lowering revision",
                    ParserDiagnosticSeverity.ERROR,
                    loweredAst.root.span
                )
            return CPlusAstCEmissionResult(
                null,
                listOf(
                    CPlusLoweringDiagnostic(
                        "CPLUS_EMIT_REPARSE_INCOMPLETE",
                        diagnostic.message,
                        diagnostic.span
                    )
                )
            )
        }
        val terminal = emit(loweredAst, lowered.source)
        if (terminal.diagnostics.isNotEmpty()) return terminal
        return terminal.copy(
            synthesizedDeclarations = lowered.synthesizedDeclarations,
            synthesizedNodes = loweredAst.synthesizedDeclarationNodes(lowered.synthesizedDeclarations, lowered.source),
            synthesizedSource = lowered.source
        )
    }

    private fun needsSeparator(
        previous: Token?,
        previousPrevious: Token?,
        current: Token,
        currentIsComment: Boolean
    ): Boolean {
        if (previous == null) return false
        val before = previous.text
        val now = current.text
        if (currentIsComment) return before !in setOf("(", "[", ".", "->")
        if (now == ":") return "conditional_expression" in current.ancestors
        if (now == "(" && before in setOf("if", "for", "while", "switch", "sizeof", "_Alignof", "return")) return true
        if (now in setOf("[", ")", "]", ",", ";", ".", "->", ":")) return false
        if (now in setOf("++", "--") && canEndExpression(previous)) return false
        if (before in setOf("++", "--")) return false
        if (before in setOf("(", "[", ".", "->")) return false
        if (isPrefixOperator(previous, previousPrevious)) return false
        if (now == "(") {
            // Calls and parenthesized postfix expressions stay attached. A
            // parenthesized operand following a binary operator remains separated.
            if ("parenthesized_declarator" in current.ancestors) return true
            return before in setOf(
                "+", "-", "*", "/", "%", "&", "|", "^", "&&", "||",
                "==", "!=", "<", ">", "<=", ">=", "<<", ">>", "=",
                "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=",
                "?"
            ) && !isPrefixOperator(previous, previousPrevious)
        }
        if (isPrefixOperator(previous, previousPrevious)) return false
        return true
    }

    private fun canEndExpression(token: Token?): Boolean {
        val spelling = token?.text ?: return false
        return spelling == ")" || spelling == "]" || spelling == "}" ||
            spelling.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) ||
            spelling.matches(Regex("(?:0[xX][0-9A-Fa-f]+|[0-9]+)(?:[uUlLfF]*)")) ||
            spelling.startsWith("\"") || spelling.startsWith("'")
    }

    private fun isPrefixOperator(token: Token, before: Token?): Boolean {
        if (token.text !in setOf("&", "*", "+", "-", "!", "~", "++", "--")) return false
        // Tree-sitter may include an outer binary expression around a unary
        // expression, so check the innermost operator context first. This matters
        // for the following token: `*p` and `a * b` need different spacing even
        // though both have `*` as `previous`.
        if (token.ancestors.any {
                it in setOf(
                    "unary_expression",
                    "pointer_expression",
                    "pointer_declarator",
                    "abstract_pointer_declarator",
                    "function_declarator"
                )
            }) {
            return true
        }
        if (token.ancestors.any { it == "binary_expression" }) {
            return false
        }
        return before == null || before.text in setOf(
            "(", "[", "{", ",", ";", ":", "?", "=", "return", "case",
            "+", "-", "*", "/", "%", "&", "|", "^", "!", "~", "&&", "||",
            "==", "!=", "<", ">", "<=", ">=", "<<", ">>", "+=", "-=", "*=", "/=", "%="
        )
    }
}

private fun CPlusAstNode.descendantsAndSelf(): Sequence<CPlusAstNode> =
    sequenceOf(this) + children.asSequence().flatMap { it.descendantsAndSelf() }
