package cplus.parser

import cplus.CPlusAstAdapter
import cplus.CPlusComptimeIndexer
import cplus.CPlusAstKind
import cplus.CPlusSynthesizedDeclaration
import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterComptimeEntityResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>,
    /** Runtime declarations materialized by this entity pass. */
    val synthesizedDeclarations: List<CPlusSynthesizedDeclaration> = emptyList()
)

/**
 * AST materialization for supported function/variable entity generators. All declaration
 * structure comes from parsed nodes; only scalar `@parameter` references and generic type-reference
 * nodes are substituted.
 */
class TreeSitterComptimeEntityLowering(
    private val maxMaterializedSourceChars: Int = MAX_MATERIALIZED_SOURCE_CHARS,
    private val targetOs: String = cplus.CPlusTarget.hostOs(),
    private val targetArch: String = cplus.CPlusTarget.hostArch()
) {
    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterComptimeEntityResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }
        require(maxMaterializedSourceChars > 0) { "materialized source limit must be positive" }
        val ast = CPlusAstAdapter().adapt(parsed)
        val index = CPlusComptimeIndexer().index(ast)
        val syntaxNodes = descendants(parsed.root).toList()
        val generators = index.constructs.filter {
            it.activeThisPass && it.moduleScope && (
                it.syntaxKind in setOf("cplus_legacy_type_generator", "cplus_legacy_function_generator") ||
                    it.syntaxKind == "cplus_comptime_function_definition" && it.resultKind in ENTITY_RESULT_KINDS + setOf("type", "code")
            )
        }
        val scopedInvocations = index.constructs.filter {
            it.activeThisPass && !it.moduleScope && it.syntaxKind in setOf(
                "cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation"
            )
        }
        if (scopedInvocations.isNotEmpty()) {
            return TreeSitterComptimeEntityResult(
                source,
                scopedInvocations.map {
                    diagnostic(
                        "CPLUS_COMPTIME_INVOCATION_SCOPE",
                        "comptime invocations are only supported at module scope in this prototype",
                        it.span
                    )
                }
            )
        }
        val invocations = index.constructs.filter {
            it.activeThisPass && it.moduleScope && it.syntaxKind in setOf(
                "cplus_comptime_invocation", "cplus_comptime_type_definition", "cplus_legacy_comptime_invocation"
            )
        }
        val ordinaryTopLevelNodes = parsed.root.children.filter {
            it.kind in setOf(
                "type_definition", "declaration", "function_definition", "cplus_function_declaration",
                "enum_specifier", "struct_specifier", "union_specifier"
            )
        }
        val existingOrdinaryNames = ordinaryTopLevelNodes.asSequence()
            .flatMap { declaration ->
                declaration.children.asSequence()
                    .filter { it.fieldName == "declarator" }
                    .mapNotNull { declarator -> descendants(declarator).firstOrNull { it.kind in IDENTIFIER_NODES } }
            }
            .map { source.text.substring(it.span.startOffset, it.span.endOffset) }
            .plus(ordinaryTopLevelNodes.asSequence()
                .filter { it.kind in setOf("type_definition", "declaration", "enum_specifier") }
                .flatMap(::descendants)
                .filter { it.kind == "enumerator" }
                .mapNotNull { enumerator -> enumerator.children.firstOrNull { it.kind in IDENTIFIER_NODES } }
                .map { source.text.substring(it.span.startOffset, it.span.endOffset) })
            .toSet()
        // C places struct, union, and enum tags in one tag namespace. Preserve kind and
        // completeness: a file-scope `struct T;` may legally be completed by the generated
        // definition, while a definition or a different tag kind cannot.
        val existingTags = ordinaryTopLevelNodes.asSequence()
            .flatMap(::fileScopeDescendants)
            .filter { it.kind in setOf("struct_specifier", "union_specifier", "enum_specifier") }
            .mapNotNull { declaration ->
                val identifier = declaration.children.firstOrNull { it.kind in IDENTIFIER_NODES } ?: return@mapNotNull null
                source.text.substring(identifier.span.startOffset, identifier.span.endOffset) to TagDeclaration(
                    kind = declaration.kind,
                    defined = declaration.children.any { child ->
                        child.kind in setOf("field_declaration_list", "enumerator_list")
                    }
                )
            }
            .groupBy({ it.first }, { it.second })
        val edits = mutableListOf<Edit>()
        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val synthesizedDeclarations = mutableListOf<CPlusSynthesizedDeclaration>()
        val usedGenerators = linkedSetOf<Int>()
        val generatedOrdinaryNames = mutableMapOf<String, cplus.SourceSpan>()
        val generatedStructTags = mutableMapOf<String, cplus.SourceSpan>()

        for (invocation in invocations) {
            // The resolver binds by symbol and arity. Keep materialization on that same
            // contract: selecting by name alone makes otherwise valid overloads disappear
            // when two generators share a symbol but accept different argument counts.
            val generator = generators.singleOrNull {
                it.symbol == invocation.symbol && it.parameters.size == invocation.argumentSpans.size
            }
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
            if (!legacyTypeGenerator && !legacyFunctionGenerator && generator.resultKind !in ENTITY_RESULT_KINDS + setOf("type", "code")) continue
            if (generator.parameters.any {
                    it.name == null || if (it.genericType) it.typeText != "type" else it.typeText?.normalizedType() !in SCALAR_TYPES
                }
            ) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_PARAMETER", "entity generators require named type parameters or named integer/bool/string parameters", generator.span)
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
                val returned = returns.singleOrNull()?.let(::returnPayload)
                entity = if (legacyFunctionGenerator && returned?.kind == "cplus_legacy_returned_function") {
                    returned.children.singleOrNull { it.kind in setOf("function_definition", "cplus_function_declaration") }
                } else returned
                if (body == null || comptimeBodyStatements(body, source).size != 1 || entity == null ||
                    entity.kind !in setOf("function_definition", "cplus_function_declaration", "declaration", "cplus_code_fragment")
                ) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_RETURN", "entity generator must return one runtime declaration or code fragment", generator.span)
                    continue
                }
                if ((legacyFunctionGenerator || generator.resultKind == "function") && entity.kind !in setOf("function_definition", "cplus_function_declaration") ||
                    generator.resultKind == "variable" && entity.kind != "declaration" ||
                    generator.resultKind == "code" && entity.kind != "cplus_code_fragment"
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
            val argumentDiagnostics = mutableListOf<CPlusLoweringDiagnostic>()
            val values = arguments.mapIndexed { index, argument ->
                val parameter = generator.parameters[index]
                val argumentNode = argument!!
                val argumentText = source.text.substring(argumentNode.span.startOffset, argumentNode.span.endOffset)
                if (parameter.genericType) parseTypeArgument(argumentNode, argumentText)
                else parseScalar(
                    parsed,
                    argumentNode,
                    argumentText,
                    parameter.typeText?.normalizedType(),
                    argumentDiagnostics
                )
            }
            if (argumentDiagnostics.isNotEmpty()) {
                diagnostics += argumentDiagnostics
                continue
            }
            if (values.any { it == null }) {
                val badIndex = values.indexOfFirst { it == null }
                val bad = arguments[badIndex]!!
                val badParameter = generator.parameters[badIndex]
                val expected = if (badParameter.genericType) "a primitive, named type, or supported C abstract type"
                else if (badParameter.typeText?.normalizedType() == "string") "a string literal"
                else "an AST-evaluable integer/bool expression"
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ARGUMENT", "this AST materialization step requires $expected for this parameter", bad.span)
                continue
            }
            val parameters = generator.parameters.mapIndexed { parameterIndex, parameter ->
                parameter.name!! to values[parameterIndex]!!
            }.toMap()
            val parameterOrigins = generator.parameters.mapIndexed { parameterIndex, parameter ->
                parameter.name!! to source.originAt(arguments[parameterIndex]!!.span.startOffset)
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
                    alias in existingOrdinaryNames || generatedOrdinaryNames.containsKey(alias) ->
                        "generated typedef name '$alias' collides with an existing ordinary identifier"
                    existingTags[materializedType.structTag].orEmpty().any {
                        it.kind != "struct_specifier" || it.defined
                    } || generatedStructTags.containsKey(materializedType.structTag) ->
                        "generated struct tag '${materializedType.structTag}' collides with another struct tag"
                    else -> null
                }
                if (collision != null) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_COLLISION", collision, invocation.span)
                    continue
                }
                generatedOrdinaryNames[alias] = invocation.span
                generatedStructTags[materializedType.structTag] = invocation.span
                edits += Edit(invocationWrapper.span.startOffset, invocationWrapper.span.endOffset, materializedType.text)
                synthesizedDeclarations += CPlusSynthesizedDeclaration(
                    kind = CPlusAstKind.TYPE_ALIAS,
                    ownerType = null,
                    sourceName = generator.symbol ?: alias,
                    generatedName = alias,
                    isStatic = false,
                    sourceSpan = invocation.span,
                    mappedText = materializedType.text,
                    originSpans = originSpans(materializedType.text)
                )
                synthesizedDeclarations += CPlusSynthesizedDeclaration(
                    kind = CPlusAstKind.STRUCT_DECLARATION,
                    ownerType = null,
                    sourceName = generator.symbol ?: materializedType.structTag,
                    generatedName = materializedType.structTag,
                    isStatic = false,
                    sourceSpan = invocation.span,
                    mappedText = materializedType.text,
                    originSpans = originSpans(materializedType.text)
                )
                usedGenerators += wrapper.span.startOffset
                continue
            }
            if (invocation.alias != null || invocationNode.children.any { it.fieldName == "alias" }) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_ALIAS", "function/variable entity generators do not accept typedef aliases", invocation.span)
                continue
            }
            val entityNodes = descendants(entity!!).toList()
            val atCallSubstitutions = entityNodes.filter { it.kind == "cplus_at_call_expression" }.mapNotNull { atCall ->
                val name = atCall.children.firstOrNull { it.kind in IDENTIFIER_NODES } ?: return@mapNotNull null
                val parameterName = source.text.substring(name.span.startOffset, name.span.endOffset)
                val replacement = parameters[parameterName] ?: return@mapNotNull null
                val start = if (name.span.startOffset > 0 && source.text[name.span.startOffset - 1] == '@') {
                    name.span.startOffset - 1
                } else {
                    atCall.span.startOffset
                }
                Edit(
                    start,
                    name.span.endOffset,
                    MappedText.generated(
                        replacement,
                        parameterOrigins[parameterName] ?: source.originAt(invocation.span.startOffset)
                    )
                )
            }
            val substitutions = comptimeParameterReferences(entity!!, source, parameters.keys)
                .filterNot { reference ->
                    atCallSubstitutions.any { it.start <= reference.span.startOffset && it.end >= reference.span.endOffset }
                }
                .mapNotNull { reference ->
                    val name = source.text.substring(reference.span.startOffset, reference.span.endOffset).removePrefix("@").trim()
                    parameters[name]?.let { value ->
                        val start = if (reference.span.startOffset > 0 && source.text[reference.span.startOffset - 1] == '@') {
                            reference.span.startOffset - 1
                        } else {
                            reference.span.startOffset
                        }
                        Edit(start, reference.span.endOffset,
                            MappedText.generated(
                                value,
                                parameterOrigins[name] ?: source.originAt(invocation.span.startOffset)
                            ))
                    }
                }.toMutableList()
            // In legacy declarations such as `@R (*callback)(...)`, Tree-sitter may parse
            // `@R (*callback)` as an at-call expression. Replace only the `@R` type token;
            // the following parenthesized declarator is ordinary C syntax and must survive.
            substitutions += atCallSubstitutions
            if (generator.resultKind == "code") {
                val fragmentBody = entity!!.children.singleOrNull { it.kind == "compound_statement" }
                if (fragmentBody == null || fragmentBody.span.endOffset - fragmentBody.span.startOffset < 2) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_CODE_FRAGMENT", "code result must contain a braced C-plus fragment", entity!!.span)
                    continue
                }
                val bodyStart = fragmentBody.span.startOffset + 1
                val bodyEnd = fragmentBody.span.endOffset - 1
                val fragment = applyFragment(source, bodyStart, bodyEnd, substitutions.distinctBy { it.start to it.end }.filter { it.start >= bodyStart && it.end <= bodyEnd })
                if (fragment == null) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_OVERLAP", "parameter substitutions overlap and cannot be materialized", entity!!.span)
                    continue
                }
                edits += Edit(invocationWrapper.span.startOffset, invocationWrapper.span.endOffset, fragment)
                usedGenerators += wrapper.span.startOffset
                continue
            }
            val entityDeclarators = entity!!.children.filter { it.fieldName == "declarator" }
            val unresolvedGeneratedName = entityDeclarators.any { declarator ->
                descendants(declarator).any { it.kind == "cplus_interpolated_identifier" }
            }
            // Names containing comptime interpolation are intentionally unresolved at this pass;
            // scalar lowering materializes them after generic parameters have been substituted.
            val entityNames = entityDeclarators.asSequence()
                .filterNot { declarator -> descendants(declarator).any { it.kind == "cplus_interpolated_identifier" } }
                .mapNotNull { declarator -> descendants(declarator).firstOrNull { it.kind in IDENTIFIER_NODES } }
                .map { name -> source.text.substring(name.span.startOffset, name.span.endOffset) }
                .toList()
            if (entityNames.isEmpty() && !unresolvedGeneratedName) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_RETURN", "generated declaration must declare at least one runtime entity", entity.span)
                continue
            }
            val collidingName = entityNames.firstOrNull { name ->
                name in existingOrdinaryNames || generatedOrdinaryNames.containsKey(name)
            }
            if (collidingName != null || entityNames.size != entityNames.toSet().size) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_ENTITY_COLLISION",
                    "generated entity '${collidingName ?: entityNames.first()}' collides with an existing ordinary identifier",
                    invocation.span
                )
                continue
            }
            val materialized = applyFragment(source, entity.span.startOffset, entity.span.endOffset, substitutions.distinctBy { it.start to it.end })
            if (materialized == null) {
                diagnostics += diagnostic("CPLUS_COMPTIME_ENTITY_OVERLAP", "parameter substitutions overlap and cannot be materialized", entity.span)
                continue
            }
            edits += Edit(invocationWrapper.span.startOffset, invocationWrapper.span.endOffset, materialized)
            entityNames.forEach { name ->
                generatedOrdinaryNames[name] = invocation.span
                synthesizedDeclarations += CPlusSynthesizedDeclaration(
                    kind = when (generator.resultKind) {
                        "function" -> CPlusAstKind.FUNCTION_DECLARATION
                        "variable" -> CPlusAstKind.VARIABLE_DECLARATION
                        else -> null
                    } ?: return@forEach,
                    ownerType = null,
                    sourceName = generator.symbol ?: name,
                    generatedName = name,
                    isStatic = false,
                    sourceSpan = invocation.span,
                    mappedText = materialized,
                    originSpans = originSpans(materialized)
                )
            }
            usedGenerators += wrapper.span.startOffset
        }

        if (diagnostics.isNotEmpty()) return TreeSitterComptimeEntityResult(source, diagnostics)
        val invocationNames = invocations.mapNotNull { it.symbol }.toSet()
        val duplicateSignatures = generators
            .groupingBy { it.symbol to it.parameters.size }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        generators.forEach { generator ->
            val node = syntaxNodes.singleOrNull { it.kind == generator.syntaxKind && it.span == generator.span } ?: return@forEach
            val unusedCanBeDiscarded = generator.span.startOffset !in usedGenerators &&
                generator.symbol !in invocationNames &&
                (generator.symbol to generator.parameters.size) !in duplicateSignatures &&
                isMaterializableEntityGenerator(node, generator, source)
            if (generator.span.startOffset !in usedGenerators && !unusedCanBeDiscarded) return@forEach
            val wrapper = node.parentWrapper(syntaxNodes) ?: node
            edits += Edit(wrapper.span.startOffset, wrapper.span.endOffset, MappedText.generated("", source.originAt(wrapper.span.startOffset)))
        }
        if (edits.isEmpty()) return TreeSitterComptimeEntityResult(source, emptyList())
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
        val materialized = output.build()
        if (materialized.text.length > maxMaterializedSourceChars && usedGenerators.isNotEmpty()) {
            val invocation = invocations.firstOrNull { it.symbol in generators.filter { generator -> generator.span.startOffset in usedGenerators }.map { it.symbol }.toSet() }
            return TreeSitterComptimeEntityResult(source, listOf(diagnostic(
                "CPLUS_COMPTIME_ENTITY_OUTPUT_LIMIT",
                "materialized comptime source exceeds the ${maxMaterializedSourceChars}-character limit",
                invocation?.span ?: parsed.source.sourceFile.span(0, 0)
            )))
        }
        return TreeSitterComptimeEntityResult(materialized, emptyList(), synthesizedDeclarations)
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
        val tag = structure.children.firstOrNull {
            it.kind in IDENTIFIER_NODES || it.kind == "cplus_interpolated_identifier"
        } ?: return null
        if (tag.span.endOffset <= tag.span.startOffset) return null
        val tagName = source.text.substring(tag.span.startOffset, tag.span.endOffset)
        val specializedTag = "${tagName}__${alias}"

        val parameterReferences = comptimeParameterReferences(structure, source, parameters.keys)
        val tagReferences = parameterReferences.filter { reference ->
            reference.span.startOffset >= tag.span.startOffset && reference.span.endOffset <= tag.span.endOffset
        }.mapNotNull { reference ->
            val name = source.text.substring(reference.span.startOffset, reference.span.endOffset).removePrefix("@").trim()
            parameters[name]?.let { replacement ->
                Edit(
                    reference.span.startOffset,
                    reference.span.endOffset,
                    MappedText.generated(replacement, source.originAt(invocation.span.startOffset))
                )
            }
        }.toList()
        val mappedTag = applyFragment(source, tag.span.startOffset, tag.span.endOffset, tagReferences) ?: return null
        val specializedTagBuilder = MappedTextBuilder().apply {
            append(mappedTag)
            appendGenerated("__${alias}", source.originAt(invocation.span.startOffset))
        }.build()
        val substitutions = (parameterReferences
            .filterNot { reference ->
                reference.span.startOffset >= tag.span.startOffset && reference.span.endOffset <= tag.span.endOffset
            }
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
            .toList() + Edit(
                tag.span.startOffset,
                tag.span.endOffset,
                specializedTagBuilder
            ))
        val mappedStructure = applyFragment(source, structure.span.startOffset, structure.span.endOffset, substitutions) ?: return null
        val output = MappedTextBuilder()
        val invocationOrigin = source.originAt(invocation.span.startOffset)
        output.appendGenerated("typedef ", invocationOrigin)
        output.append(mappedStructure)
        output.appendGenerated(" $alias;\n", invocationOrigin)
        return MaterializedType(output.build(), specializedTag)
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
        val substitutions = comptimeParameterReferences(structure, source, parameters.keys)
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
     * Comptime parameters appear as explicit @-references in the AST; generic type parameters
     * also appear as ordinary type identifiers and in C type-query contexts such as sizeof(T).
     * Restrict ordinary identifier substitutions to type-query contexts so unrelated runtime
     * identifiers with the same spelling are never rewritten.
     */
    private fun comptimeParameterReferences(
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
        // A generic type parameter passed to another comptime function is an ordinary identifier
        // in the CST (`@typename(T)`), not a type_identifier. Bind it only inside an AST-recognized
        // comptime call argument list, leaving same-spelled runtime identifiers untouched.
        val comptimeCallTypeArguments = nodes.asSequence()
            .filter { it.kind == "cplus_at_call_expression" }
            .flatMap { call ->
                call.children.asSequence()
                    .filter { it.kind == "argument_list" }
                    .flatMap(::descendants)
            }
            .filter { node ->
                node.kind in IDENTIFIER_NODES &&
                    source.text.substring(node.span.startOffset, node.span.endOffset) in parameters
            }
        return (explicitTypeNodes.asSequence() + queriedTypeIdentifiers + comptimeCallTypeArguments)
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
            if (edit.start < cursor || edit.end > end) {
                return null
            }
            output.append(source, cursor, edit.start)
            output.append(edit.text)
            cursor = edit.end
        }
        output.append(source, cursor, end)
        return output.build()
    }

    /** Compress copied template/invocation origins into spans for semantic-node matching. */
    private fun originSpans(mapped: MappedText): List<cplus.SourceSpan> {
        val spans = mutableListOf<cplus.SourceSpan>()
        var file: cplus.SourceFile? = null
        var start = -1
        var end = -1
        fun flush() {
            val current = file ?: return
            if (start >= 0) spans += current.span(start, end + 1)
            file = null
            start = -1
            end = -1
        }
        for (index in mapped.text.indices) {
            val origin = mapped.originAt(index)
            val nextFile = origin?.file
            val nextOffset = origin?.offset ?: -1
            val contiguous = nextFile === file && nextOffset == end + 1
            if (!contiguous) flush()
            if (nextFile != null) {
                if (file == null) {
                    file = nextFile
                    start = nextOffset
                }
                end = nextOffset
            }
        }
        flush()
        return spans
    }

    /** Do not erase an uninstantiated declaration unless its return shape is in this pass's subset. */
    private fun isMaterializableEntityGenerator(
        node: CPlusSyntaxNode,
        generator: cplus.CPlusComptimeConstruct,
        source: MappedText
    ): Boolean {
        val legacyType = generator.syntaxKind == "cplus_legacy_type_generator"
        val legacyFunction = generator.syntaxKind == "cplus_legacy_function_generator"
        val body = node.children.firstOrNull { it.fieldName == "body" } ?: return false
        val statements = comptimeBodyStatements(body, source)
        if (statements.size != 1) return false

        if (legacyType) {
            val returnedStruct = statements.single().takeIf { it.kind == "return_statement" }
                ?.let(::returnPayload)?.takeIf { it.kind == "struct_specifier" } ?: return false
            return returnedStruct.children.any { it.kind == "field_declaration_list" } &&
                returnedStruct.children.none { it.kind in IDENTIFIER_NODES }
        }

        val returnNode = statements.single().takeIf {
            it.kind in setOf("return_statement", "cplus_comptime_return_declaration")
        } ?: return false
        val returned = returnPayload(returnNode) ?: return false
        if (generator.resultKind == "type") {
            val fragment = descendants(returned).singleOrNull { it.kind == "cplus_code_fragment" } ?: return false
            val fragmentBody = fragment.children.singleOrNull { it.kind == "compound_statement" } ?: return false
            val fragmentStatements = fragmentBody.children.filter { it.named && it.kind != "comment" }
            return fragmentStatements.singleOrNull()?.kind == "struct_specifier"
        }
        if (generator.resultKind == "code") {
            val fragment = descendants(returned).singleOrNull { it.kind == "cplus_code_fragment" } ?: return false
            return fragment.children.singleOrNull { it.kind == "compound_statement" } != null
        }
        val entity = if (legacyFunction && returned.kind == "cplus_legacy_returned_function") {
            returned.children.singleOrNull { it.kind == "cplus_function_declaration" }
        } else returned
        return when {
            legacyFunction -> entity?.kind == "cplus_function_declaration"
            generator.resultKind == "function" -> entity?.kind in setOf("function_definition", "cplus_function_declaration")
            generator.resultKind == "variable" -> entity?.kind == "declaration"
            else -> false
        }
    }

    /**
     * Tree-sitter exposes the `return` keyword as a named leaf in these grammar productions.
     * Runtime declarations may also be followed by the conventional semicolon in a comptime
     * return body (`return int f(...) { ... };`), represented as an empty expression statement.
     */
    private fun returnPayload(node: CPlusSyntaxNode): CPlusSyntaxNode? =
        node.children.singleOrNull { it.kind !in setOf("return", "comment", ";") }

    private fun comptimeBodyStatements(body: CPlusSyntaxNode, source: MappedText): List<CPlusSyntaxNode> =
        body.children.filter { child ->
            child.named && child.kind != "comment" &&
                !(child.kind == "expression_statement" &&
                    source.text.substring(child.span.startOffset, child.span.endOffset).trim() == ";")
        }

    private data class Edit(val start: Int, val end: Int, val text: MappedText)

    private data class MaterializedType(val text: MappedText, val structTag: String)

    private data class TagDeclaration(val kind: String, val defined: Boolean)

    private fun parseScalar(
        parsed: CPlusParseResult,
        argument: CPlusSyntaxNode,
        raw: String,
        parameterType: String?,
        diagnostics: MutableList<CPlusLoweringDiagnostic>
    ): String? {
        val value = raw.trim()
        if (parameterType == "string") return value.takeIf { argument.kind in STRING_LITERAL_NODES }
        val evaluated = TreeSitterComptimeScalarLowering(targetOs, targetArch)
            .evaluateEntityArgument(parsed, argument)
        diagnostics += evaluated.diagnostics
        return evaluated.renderedValue
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
        if (declarator.kind !in ABSTRACT_TYPE_DECLARATOR_NODES || !isSupportedAbstractType(declarator)) return null
        return type
    }

    private fun isSupportedAbstractType(node: CPlusSyntaxNode): Boolean =
        descendants(node).none { it.isError || it.isMissing || it.kind.startsWith("cplus_") }

    private fun String.normalizedType(): String = replace(Regex("\\s+"), " ").trim()

    private fun CPlusSyntaxNode.parentWrapper(all: List<CPlusSyntaxNode>): CPlusSyntaxNode? = all
        .asSequence()
        .filter { it.kind == "cplus_comptime_declaration" && it.span.startOffset <= span.startOffset && it.span.endOffset >= span.endOffset }
        .minByOrNull { it.span.endOffset - it.span.startOffset }

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

    /** Walk file-scope declarations without importing prototype- or block-scope tags. */
    private fun fileScopeDescendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequence {
            yield(node)
            if (node.kind in setOf("compound_statement", "parameter_list")) return@sequence
            node.children.asSequence()
                .filterNot { node.kind == "function_definition" && it.fieldName == "body" }
                .flatMap(::fileScopeDescendants)
                .forEach { yield(it) }
        }

    private fun diagnostic(code: String, message: String, span: cplus.SourceSpan) =
        CPlusLoweringDiagnostic(code, message, span)

    private companion object {
        const val MAX_MATERIALIZED_SOURCE_CHARS = 8 * 1024 * 1024
        val ENTITY_RESULT_KINDS = setOf("function", "variable")
        val IDENTIFIER_NODES = setOf("identifier", "type_identifier")
        val SUPPORTED_TYPE_SPECIFIERS = setOf(
            "primitive_type", "sized_type_specifier", "type_identifier", "struct_specifier", "union_specifier", "enum_specifier"
        )
        val ABSTRACT_TYPE_DECLARATOR_NODES = setOf(
            "abstract_pointer_declarator", "abstract_array_declarator",
            "abstract_function_declarator", "abstract_parenthesized_declarator"
        )
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
            "unsigned long long", "unsigned long long int", "bool", "_Bool", "string"
        )
        val STRING_LITERAL_NODES = setOf("string_literal", "concatenated_string")
    }
}
