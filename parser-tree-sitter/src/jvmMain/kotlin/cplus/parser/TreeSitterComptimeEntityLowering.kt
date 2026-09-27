package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusComptimeIndexer
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterComptimeEntityResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/**
 * AST materialization for supported function/variable entity generators. All declaration
 * structure comes from parsed nodes; only scalar `@parameter` references and generic type-reference
 * nodes are substituted.
 */
class TreeSitterComptimeEntityLowering {
    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterComptimeEntityResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        val ast = CPlusAstAdapter().adapt(parsed)
        val index = CPlusComptimeIndexer().index(ast)
        val syntaxNodes = descendants(parsed.root).toList()
        val generators = index.constructs.filter {
            it.activeThisPass && it.moduleScope && (
                it.syntaxKind in setOf("cplus_legacy_type_generator", "cplus_legacy_function_generator") ||
                    it.syntaxKind == "cplus_comptime_function_definition" && it.resultKind in ENTITY_RESULT_KINDS + "type"
                )
        }
        val invocations = index.constructs.filter {
            it.activeThisPass && it.syntaxKind in setOf("cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation")
        }
        val ordinaryTopLevelNodes = parsed.root.children.filter { it.kind in setOf("type_definition", "declaration", "struct_specifier") }
        val existingTypeAliases = ordinaryTopLevelNodes.filter { it.kind == "type_definition" }
            .flatMap { declaration -> declaration.children.filter { it.fieldName == "declarator" } }
            .mapNotNull { declarator -> descendants(declarator).firstOrNull { it.kind in IDENTIFIER_NODES } }
            .map { source.text.substring(it.span.startOffset, it.span.endOffset) }
            .toSet()
        val existingStructTags = ordinaryTopLevelNodes.asSequence()
            .flatMap(::descendants)
            .filter { it.kind == "struct_specifier" }
            .mapNotNull { structure -> structure.children.firstOrNull { it.kind in IDENTIFIER_NODES } }
            .map { source.text.substring(it.span.startOffset, it.span.endOffset) }
            .toSet()
        val edits = mutableListOf<Edit>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val usedGenerators = linkedSetOf<Int>()
        val generatedTypeAliases = mutableMapOf<String, cplus.SourceSpan>()
        val generatedStructTags = mutableMapOf<String, cplus.SourceSpan>()

        for (invocation in invocations) {
            val generator = generators.singleOrNull { it.symbol == invocation.symbol }
                ?: continue // The shared comptime resolver diagnoses unknown/ambiguous bindings.
            val declarationNode = syntaxNodes.singleOrNull {
                it.kind == generator.syntaxKind && it.span == generator.span
            }
            if (declarationNode == null) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_AST", "generator binding has no corresponding syntax node", generator.span)
                continue
            }
            val invocationNode = syntaxNodes.singleOrNull {
                it.kind == invocation.syntaxKind && it.span == invocation.span
            }
            if (invocationNode == null) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_AST", "invocation binding has no corresponding syntax node", invocation.span)
                continue
            }
            val wrapper = declarationNode.parentWrapper(syntaxNodes)
                ?: declarationNode
            val invocationWrapper = invocationNode.parentWrapper(syntaxNodes)
                ?: invocationNode

            val legacyTypeGenerator = generator.syntaxKind == "cplus_legacy_type_generator"
            val legacyFunctionGenerator = generator.syntaxKind == "cplus_legacy_function_generator"
            val typeGenerator = legacyTypeGenerator || !legacyFunctionGenerator && generator.resultKind == "type"
            if (!legacyTypeGenerator && !legacyFunctionGenerator && generator.resultKind !in ENTITY_RESULT_KINDS + "type") continue
            if (generator.parameters.any {
                    it.name == null || if (it.genericType) it.typeText != "type" else it.typeText?.normalizedType() !in SCALAR_TYPES
                }
            ) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_PARAMETER", "entity generators require named type parameters or named integer/bool parameters", generator.span)
                continue
            }
            if (invocation.argumentSpans.size != generator.parameters.size) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ARITY", "generator '${generator.symbol}' expects ${generator.parameters.size} argument(s), got ${invocation.argumentSpans.size}", invocation.span)
                continue
            }
            var entity: CPlusSyntaxNode? = null
            if (!typeGenerator) {
                val body = declarationNode.children.firstOrNull { it.fieldName == "body" }
                val returns = body?.children.orEmpty().filter { it.kind in setOf("return_statement", "cplus_comptime_return_declaration") }
                val returned = returns.singleOrNull()?.children?.singleOrNull { it.named && it.kind != "comment" }
                entity = if (legacyFunctionGenerator && returned?.kind == "cplus_legacy_returned_function") {
                    returned.children.singleOrNull { it.kind in setOf("function_definition", "cplus_function_declaration") }
                } else returned
                if (body == null || body.children.count { it.named && it.kind != "comment" } != 1 || entity == null ||
                    entity.kind !in setOf("function_definition", "cplus_function_declaration", "declaration")
                ) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_RETURN", "entity generator must return exactly one runtime function or variable declaration", generator.span)
                    continue
                }
                if ((legacyFunctionGenerator || generator.resultKind == "function") && entity.kind !in setOf("function_definition", "cplus_function_declaration") ||
                    generator.resultKind == "variable" && entity.kind != "declaration"
                ) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_KIND", "generator result kind '${generator.resultKind}' does not match its returned declaration", entity.span)
                    continue
                }
            }
            val arguments = invocation.argumentSpans.map { span ->
                syntaxNodes.firstOrNull { it.span == span && it.kind !in setOf("comment", "ERROR") }
            }
            if (arguments.any { it == null }) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ARGUMENT", "could not identify a generator argument expression in the AST", invocation.span)
                continue
            }
            val values = arguments.mapIndexed { index, argument ->
                val parameter = generator.parameters[index]
                val argumentNode = argument!!
                val argumentText = source.text.substring(argumentNode.span.startOffset, argumentNode.span.endOffset)
                if (parameter.genericType) parseTypeArgument(argumentNode, argumentText) else parseScalar(argumentText)
            }
            if (values.any { it == null }) {
                val badIndex = values.indexOfFirst { it == null }
                val bad = arguments[badIndex]!!
                val expected = if (generator.parameters[badIndex].genericType) "a primitive or named type token" else "an integer/bool literal"
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ARGUMENT", "this AST materialization step requires $expected for this parameter", bad.span)
                continue
            }
            val parameters = generator.parameters.mapIndexed { parameterIndex, parameter ->
                parameter.name!! to values[parameterIndex]!!
            }.toMap()
            if (typeGenerator) {
                val alias = invocation.alias
                if (alias == null) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ALIAS", "a type generator invocation must provide a typedef alias", invocation.span)
                    continue
                }
                val typeParameters = generator.parameters.filter { it.genericType }.mapNotNull { parameter ->
                    parameter.name?.let { name -> parameters[name]?.let { name to it } }
                }.toMap()
                val materializedType = if (legacyTypeGenerator) {
                    materializeLegacyTypeEntity(declarationNode, source, typeParameters, alias, invocation)
                } else {
                    materializeTypeEntity(declarationNode, source, typeParameters, alias, invocation)
                }
                if (materializedType == null) {
                    diagnostics += diagnostic(
                        "CPLUS_COMPTIME_ENTITY_RETURN",
                        if (legacyTypeGenerator) "legacy type generator must return exactly one struct definition" else
                            "type generator must return one @code fragment containing exactly one named struct definition",
                        generator.span
                    )
                    continue
                }
                val collision = when {
                    alias in existingTypeAliases || generatedTypeAliases.containsKey(alias) -> "generated typedef alias '$alias' collides with another type"
                    materializedType.structTag in existingStructTags || generatedStructTags.containsKey(materializedType.structTag) ->
                        "generated struct tag '${materializedType.structTag}' collides with another struct tag"
                    else -> null
                }
                if (collision != null) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_COLLISION", collision, invocation.span)
                    continue
                }
                generatedTypeAliases[alias] = invocation.span
                generatedStructTags[materializedType.structTag] = invocation.span
                edits += Edit(invocationWrapper.span.startOffset, invocationWrapper.span.endOffset, materializedType.text)
                usedGenerators += wrapper.span.startOffset
                continue
            }
            if (invocation.alias != null || invocationNode.children.any { it.fieldName == "alias" }) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ALIAS", "function/variable entity generators do not accept typedef aliases", invocation.span)
                continue
            }
            val genericParameters = generator.parameters.filter { it.genericType }.mapNotNull { it.name }.toSet()
            val entityNodes = descendants(entity!!).toList()
            val substitutions = entityNodes
                .filter {
                    it.kind == "cplus_type_reference" ||
                        it.kind == "type_identifier" && source.text.substring(it.span.startOffset, it.span.endOffset) in genericParameters
                }
                .mapNotNull { reference ->
                    val name = source.text.substring(reference.span.startOffset, reference.span.endOffset).removePrefix("@").trim()
                    parameters[name]?.let { value ->
                        Edit(reference.span.startOffset, reference.span.endOffset,
                            MappedText.generated(value, source.originAt(invocation.span.startOffset)))
                    }
                }.toMutableList()
            // In legacy declarations such as `@R (*callback)(...)`, Tree-sitter may parse
            // `@R (*callback)` as an at-call expression. Replace only the `@R` type token;
            // the following parenthesized declarator is ordinary C syntax and must survive.
            entityNodes.filter { it.kind == "cplus_at_call_expression" }.forEach { atCall ->
                val name = atCall.children.firstOrNull { it.kind in IDENTIFIER_NODES } ?: return@forEach
                val parameterName = source.text.substring(name.span.startOffset, name.span.endOffset)
                val replacement = parameters[parameterName] ?: return@forEach
                substitutions += Edit(
                    atCall.span.startOffset,
                    name.span.endOffset,
                    MappedText.generated(replacement, source.originAt(invocation.span.startOffset))
                )
            }
            val materialized = applyFragment(source, entity.span.startOffset, entity.span.endOffset, substitutions)
            if (materialized == null) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_OVERLAP", "parameter substitutions overlap and cannot be materialized", entity.span)
                continue
            }
            edits += Edit(invocationWrapper.span.startOffset, invocationWrapper.span.endOffset, materialized)
            usedGenerators += wrapper.span.startOffset
        }

        if (diagnostics.isNotEmpty()) return TreeSitterComptimeEntityResult(source, diagnostics)
        if (edits.isEmpty()) return TreeSitterComptimeEntityResult(source, emptyList())
        generators.filter { it.span.startOffset in usedGenerators }.forEach { generator ->
            val node = syntaxNodes.singleOrNull { it.kind == generator.syntaxKind && it.span == generator.span } ?: return@forEach
            val wrapper = node.parentWrapper(syntaxNodes) ?: node
            edits += Edit(wrapper.span.startOffset, wrapper.span.endOffset, MappedText.generated("", source.originAt(wrapper.span.startOffset)))
        }
        edits.sortBy { it.start }
        val output = MappedTextBuilder()
        var cursor = 0
        for (edit in edits) {
            if (edit.start < cursor) {
                return TreeSitterComptimeEntityResult(source, listOf(diagnostic(
                    "CPLUS_COMPTIME_ENTITY_OVERLAP", "comptime entity expansions overlap and cannot be materialized in one pass", parsed.source.sourceFile.span(edit.start, edit.end)
                )))
            }
            output.append(source, cursor, edit.start)
            output.append(edit.text)
            cursor = edit.end
        }
        output.append(source, cursor, source.text.length)
        return TreeSitterComptimeEntityResult(output.build(), emptyList())
    }

    private fun materializeTypeEntity(
        generator: CPlusSyntaxNode,
        source: MappedText,
        parameters: Map<String, String>,
        alias: String,
        invocation: cplus.CPlusComptimeConstruct
    ): MaterializedType? {
        if (!alias.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        val body = generator.children.firstOrNull { it.fieldName == "body" } ?: return null
        val returns = body.children.filter { it.kind == "return_statement" }
        if (returns.size != 1 || body.children.count { it.named && it.kind != "comment" } != 1) return null
        val codeFragment = descendants(returns.single()).filter { it.kind == "cplus_code_fragment" }.singleOrNull() ?: return null
        val fragmentBody = codeFragment.children.singleOrNull { it.kind == "compound_statement" } ?: return null
        val statements = fragmentBody.children.filter { it.named && it.kind != "comment" }
        if (statements.size != 1 || statements.single().kind != "struct_specifier") return null
        val structure = statements.single()
        val tag = structure.children.firstOrNull { it.kind in IDENTIFIER_NODES } ?: return null
        if (tag.span.endOffset <= tag.span.startOffset) return null
        val tagName = source.text.substring(tag.span.startOffset, tag.span.endOffset)

        val substitutions = typeParameterReferences(structure, source, parameters.keys)
            .mapNotNull { reference ->
                val name = source.text.substring(reference.span.startOffset, reference.span.endOffset).removePrefix("@").trim()
                parameters[name]?.let { replacement ->
                    Edit(
                        reference.span.startOffset,
                        reference.span.endOffset,
                        MappedText.generated(replacement, source.originAt(invocation.span.startOffset))
                    )
                }
            }
            .toList()
        val mappedStructure = applyFragment(source, structure.span.startOffset, structure.span.endOffset, substitutions) ?: return null
        val output = MappedTextBuilder()
        val invocationOrigin = source.originAt(invocation.span.startOffset)
        output.appendGenerated("typedef ", invocationOrigin)
        output.append(mappedStructure)
        output.appendGenerated(" $alias;\n", invocationOrigin)
        return MaterializedType(output.build(), tagName)
    }

    private fun materializeLegacyTypeEntity(
        generator: CPlusSyntaxNode,
        source: MappedText,
        parameters: Map<String, String>,
        alias: String,
        invocation: cplus.CPlusComptimeConstruct
    ): MaterializedType? {
        if (!alias.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        val body = generator.children.firstOrNull { it.fieldName == "body" } ?: return null
        val returns = body.children.filter { it.kind == "return_statement" }
        if (returns.size != 1 || body.children.count { it.named && it.kind != "comment" } != 1) return null
        val structure = returns.single().children.singleOrNull { it.kind == "struct_specifier" } ?: return null
        if (structure.children.any { it.kind in IDENTIFIER_NODES }) return null
        val fields = structure.children.singleOrNull { it.kind == "field_declaration_list" } ?: return null
        val braceStart = source.text.indexOf('{', fields.span.startOffset).takeIf { it < fields.span.endOffset } ?: return null
        val braceEnd = source.text.lastIndexOf('}', fields.span.endOffset - 1).takeIf { it >= braceStart } ?: return null
        val substitutions = typeParameterReferences(structure, source, parameters.keys)
            .mapNotNull { reference ->
                val name = source.text.substring(reference.span.startOffset, reference.span.endOffset).removePrefix("@").trim()
                parameters[name]?.let { replacement ->
                    Edit(reference.span.startOffset, reference.span.endOffset,
                        MappedText.generated(replacement, source.originAt(invocation.span.startOffset)))
                }
            }.toList()
        val mappedBody = applyFragment(source, braceStart, braceEnd + 1, substitutions) ?: return null
        val origin = source.originAt(invocation.span.startOffset)
        val output = MappedTextBuilder()
        output.appendGenerated("typedef struct $alias ", origin)
        output.append(mappedBody)
        output.appendGenerated(" $alias;\n", origin)
        return MaterializedType(output.build(), alias)
    }

    /**
     * Type parameters appear as type nodes in declarations and, in C's sizeof(T) expression
     * grammar, as identifiers. Restrict the latter substitution to AST type-query contexts so an
     * unrelated runtime identifier with the same spelling is never rewritten.
     */
    private fun typeParameterReferences(
        root: CPlusSyntaxNode,
        source: MappedText,
        parameters: Set<String>
    ): Sequence<CPlusSyntaxNode> {
        val nodes = descendants(root).toList()
        val explicitTypeNodes = nodes.filter { it.kind == "cplus_type_reference" || it.kind == "type_identifier" }
        val queriedTypeIdentifiers = nodes.asSequence()
            .filter { it.kind in TYPE_QUERY_NODES }
            .flatMap(::descendants)
            .filter { node ->
                node.kind == "identifier" && source.text.substring(node.span.startOffset, node.span.endOffset) in parameters
            }
        return (explicitTypeNodes.asSequence() + queriedTypeIdentifiers)
            .distinctBy { it.span.startOffset to it.span.endOffset }
            .filter { node ->
                val name = source.text.substring(node.span.startOffset, node.span.endOffset).removePrefix("@").trim()
                name in parameters
            }
    }

    private fun applyFragment(source: MappedText, start: Int, end: Int, edits: List<Edit>): MappedText? {
        val output = MappedTextBuilder()
        var cursor = start
        edits.sortedBy { it.start }.forEach { edit ->
            if (edit.start < cursor || edit.end > end) return null
            output.append(source, cursor, edit.start)
            output.append(edit.text)
            cursor = edit.end
        }
        output.append(source, cursor, end)
        return output.build()
    }

    private data class Edit(val start: Int, val end: Int, val text: MappedText)

    private data class MaterializedType(val text: MappedText, val structTag: String)

    private fun parseScalar(raw: String): String? {
        val value = raw.trim()
        if (value == "true") return "1"
        if (value == "false") return "0"
        val number = value.replace(Regex("(?i)[ul]+$"), "")
        val parsed = when {
            number.startsWith("0x", true) -> number.drop(2).toLongOrNull(16)
            number.startsWith("0b", true) -> number.drop(2).toLongOrNull(2)
            number.length > 1 && number.startsWith('0') -> number.drop(1).toLongOrNull(8)
            else -> number.toLongOrNull()
        } ?: return null
        return parsed.toString()
    }

    private fun parseTypeArgument(argument: CPlusSyntaxNode, raw: String): String? {
        val type = raw.trim()
        if (argument.kind in IDENTIFIER_NODES && type.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return type
        if (argument.kind == "primitive_type" && type in PRIMITIVE_TYPES) return type
        if (argument.kind != "cplus_type_argument") return null
        val descriptor = argument.children.singleOrNull { it.kind == "type_descriptor" } ?: return null
        val baseType = descriptor.children.firstOrNull { it.fieldName == "type" } ?: return null
        if (baseType.kind !in SUPPORTED_TYPE_SPECIFIERS) return null
        val declarator = descriptor.children.firstOrNull { it.fieldName == "declarator" } ?: return type
        if (declarator.kind !in ABSTRACT_POINTER_NODES || !isPointerOnlyDeclarator(declarator)) return null
        return type
    }

    private fun isPointerOnlyDeclarator(node: CPlusSyntaxNode): Boolean = when (node.kind) {
        "abstract_pointer_declarator" -> node.children.all { child ->
            !child.named || child.kind in POINTER_QUALIFIER_NODES ||
                child.kind in ABSTRACT_POINTER_NODES && isPointerOnlyDeclarator(child)
        }
        "abstract_parenthesized_declarator" -> node.children.filter { it.named }.singleOrNull()
            ?.let(::isPointerOnlyDeclarator) == true
        else -> false
    }

    private fun String.normalizedType(): String = replace(Regex("\\s+"), " ").trim()

    private fun CPlusSyntaxNode.parentWrapper(all: List<CPlusSyntaxNode>): CPlusSyntaxNode? = all
        .asSequence()
        .filter { it.kind == "cplus_comptime_declaration" && it.span.startOffset <= span.startOffset && it.span.endOffset >= span.endOffset }
        .minByOrNull { it.span.endOffset - it.span.startOffset }

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

    private fun diagnostic(code: String, message: String, span: cplus.SourceSpan) =
        CPlusLoweringDiagnostic(code, message, span)

    private companion object {
        val ENTITY_RESULT_KINDS = setOf("function", "variable")
        val IDENTIFIER_NODES = setOf("identifier", "type_identifier")
        val SUPPORTED_TYPE_SPECIFIERS = setOf("primitive_type", "type_identifier", "struct_specifier", "union_specifier", "enum_specifier")
        val ABSTRACT_POINTER_NODES = setOf("abstract_pointer_declarator", "abstract_parenthesized_declarator")
        val POINTER_QUALIFIER_NODES = setOf("type_qualifier", "ms_pointer_modifier")
        val TYPE_QUERY_NODES = setOf("sizeof_expression", "alignof_expression", "offsetof_expression")
        val PRIMITIVE_TYPES = setOf(
            "void", "char", "signed char", "unsigned char", "short", "short int", "unsigned short",
            "int", "unsigned", "unsigned int", "long", "unsigned long", "long long", "unsigned long long",
            "float", "double", "_Bool", "bool"
        )
        val SCALAR_TYPES = setOf(
            "char", "signed char", "unsigned char", "short", "short int", "signed short", "signed short int",
            "unsigned short", "unsigned short int", "int", "signed", "signed int", "unsigned", "unsigned int",
            "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int",
            "long long", "long long int", "signed long long", "signed long long int",
            "unsigned long long", "unsigned long long int", "bool", "_Bool"
        )
    }
}
