package cplus.parser

import cplus.CPlusLoweringDiagnostic
import cplus.CPlusParseResult
import cplus.CPlusSyntaxNode
import cplus.MappedText
import cplus.MappedTextBuilder

data class TreeSitterComptimeFieldLoopResult(
    val source: MappedText,
    val diagnostics: List<CPlusLoweringDiagnostic>
)

/** Expands the bounded `@for field in Struct.fields` reflection form using syntax-tree metadata. */
class TreeSitterComptimeFieldLoopLowering(
    targetOs: String = cplus.CPlusTarget.hostOs(),
    targetArch: String = cplus.CPlusTarget.hostArch()
) {
    private val targetOs = cplus.CPlusTarget.normalizeOs(targetOs)
    private val targetArch = cplus.CPlusTarget.normalizeArch(targetArch)

    fun lower(parsed: CPlusParseResult, source: MappedText): TreeSitterComptimeFieldLoopResult {
        require(parsed.source.text == source.text) { "AST and mapped source must contain the same snapshot text" }

        val structures = reflectedStructs(
            parsed.root,
            parsed.source.text,
            computeTreeSitterAbiAggregateLayouts(parsed.root, parsed.source.text, targetOs, targetArch)
        )
        val loops = mutableListOf<Pair<CPlusSyntaxNode, Boolean>>()
        fun collect(
            node: CPlusSyntaxNode,
            inComptimeBlock: Boolean,
            comptimeBlockAtModuleScope: Boolean,
            moduleScope: Boolean,
            insideLoop: Boolean = false
        ) {
            if (node.kind in GENERATOR_DEFINITION_NODES) return
            if (node.kind == LOOP_KIND && !insideLoop) loops += node to (inComptimeBlock && comptimeBlockAtModuleScope)
            val startsBlock = node.kind == "cplus_comptime_block"
            val nextInBlock = inComptimeBlock || startsBlock
            val nextBlockAtModuleScope = if (startsBlock) moduleScope else comptimeBlockAtModuleScope
            val childrenAtModuleScope = moduleScope &&
                (node.kind == "translation_unit" || node.kind in PREPROCESSOR_CONTAINERS)
            node.children.forEach {
                collect(it, nextInBlock, nextBlockAtModuleScope, childrenAtModuleScope, insideLoop || node.kind == LOOP_KIND)
            }
        }
        collect(parsed.root, false, false, true)
        if (loops.isEmpty()) return TreeSitterComptimeFieldLoopResult(source, emptyList())

        val diagnostics = mutableListOf<CPlusLoweringDiagnostic>()
        val replacements = loops.mapNotNull { (loop, inComptimeBlock) ->
            if (!inComptimeBlock) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_FIELD_LOOP_SCOPE",
                    "reflected-field loops are only valid inside a comptime block",
                    loop.span
                )
                return@mapNotNull null
            }
            val expanded = expandLoop(
                loop,
                emptyMap(),
                structures,
                source,
                parsed.source.text,
                diagnostics,
                nested = false
            )
            expanded?.let { Replacement(loop.span.startOffset, loop.span.endOffset, it) }
        }
        if (diagnostics.isNotEmpty()) return TreeSitterComptimeFieldLoopResult(source, diagnostics.distinct())

        val output = MappedTextBuilder()
        var cursor = 0
        replacements.sortedBy { it.start }.forEach { replacement ->
            if (replacement.start < cursor) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_FIELD_LOOP_OVERLAP",
                    "overlapping reflected-field loops cannot be expanded in one pass",
                    parsed.source.sourceFile.span(replacement.start, replacement.end)
                )
            } else {
                output.append(source, cursor, replacement.start)
                output.append(replacement.content)
                cursor = replacement.end
            }
        }
        if (diagnostics.isNotEmpty()) return TreeSitterComptimeFieldLoopResult(source, diagnostics)
        output.append(source, cursor, source.text.length)
        return TreeSitterComptimeFieldLoopResult(output.build(), emptyList())
    }

    private fun expandLoop(
        loop: CPlusSyntaxNode,
        bindings: Map<String, IterationValue>,
        structures: Map<String, ReflectedStructure>,
        source: MappedText,
        text: String,
        diagnostics: MutableList<CPlusLoweringDiagnostic>,
        nested: Boolean,
        budget: ExpansionBudget = ExpansionBudget()
    ): MappedText? {
        val variable = loop.children.firstOrNull { it.fieldName == "variable" }
        val iterable = loop.children.firstOrNull { it.fieldName == "iterable" }
        val body = loop.children.firstOrNull { it.fieldName == "body" }
        val variableName = variable?.let { textOf(it, text) }
        if (variableName == null || body == null) {
            diagnostics += diagnostic(
                if (nested) "CPLUS_COMPTIME_FIELD_LOOP_NESTED_ITERABLE" else "CPLUS_COMPTIME_FIELD_LOOP_ITERABLE",
                "@for requires a named iteration variable and a compound body",
                iterable?.span ?: loop.span
            )
            return null
        }
        val values = resolveIterable(loop, bindings, structures, text, diagnostics, nested) ?: return null
        if (values.size > MAX_REFLECTED_FIELDS) {
            diagnostics += diagnostic(
                "CPLUS_COMPTIME_FIELD_LOOP_LIMIT",
                "reflected-field loop exceeds the $MAX_REFLECTED_FIELDS-field expansion limit",
                loop.span
            )
            return null
        }
        val expanded = MappedTextBuilder()
        values.forEach { value ->
            if (!budget.consume()) {
                val message = "nested reflected-field expansion exceeds the $MAX_TOTAL_EXPANSION_ITEMS-item limit"
                if (diagnostics.none { it.code == "CPLUS_COMPTIME_FIELD_LOOP_LIMIT" && it.message == message }) {
                    diagnostics += diagnostic("CPLUS_COMPTIME_FIELD_LOOP_LIMIT", message, loop.span)
                }
                return@forEach
            }
            val scopedBindings = bindings + (variableName to value)
            val bodyExpansion = expandBody(
                body,
                scopedBindings,
                structures,
                source,
                text,
                diagnostics,
                budget
            ) ?: return@forEach
            expanded.append(bodyExpansion)
        }
        return if (diagnostics.isEmpty()) expanded.build() else null
    }

    private fun resolveIterable(
        loop: CPlusSyntaxNode,
        bindings: Map<String, IterationValue>,
        structures: Map<String, ReflectedStructure>,
        text: String,
        diagnostics: MutableList<CPlusLoweringDiagnostic>,
        nested: Boolean
    ): List<IterationValue>? {
        val iterable = loop.children.firstOrNull { it.fieldName == "iterable" }
        val iterableArgument = iterable?.takeIf { it.kind == "field_expression" }
            ?.children?.firstOrNull { it.fieldName == "argument" }
        val iterableField = iterable?.children?.firstOrNull { it.fieldName == "field" }
        val property = iterableField?.let { textOf(it, text) }
        val argumentName = iterableArgument?.let { textOf(it, text) }
        val nestedCode = if (nested) "CPLUS_COMPTIME_FIELD_LOOP_NESTED_ITERABLE" else "CPLUS_COMPTIME_FIELD_LOOP_ITERABLE"
        if (iterableArgument == null || property == null || argumentName == null) {
            diagnostics += diagnostic(
                nestedCode,
                "@for requires a reflected `.fields` or `.annotations` iterable",
                iterable?.span ?: loop.span
            )
            return null
        }
        if (property == "fields" && iterableArgument.kind in TYPE_NAME_NODES) {
            val typeName = argumentName
            val structure = structures[typeName]
            if (structure == null) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_REFLECTION_FIELDS",
                    "field metadata is unavailable for '$typeName'; define a complete struct in this source",
                    iterableArgument.span
                )
                return null
            }
            if (structure.invalidFieldSpan != null) {
                diagnostics += diagnostic(
                    "CPLUS_COMPTIME_REFLECTION_FIELD_SHAPE",
                    "field metadata for '$typeName' contains a declarator shape not supported by this prototype",
                    structure.invalidFieldSpan
                )
                return null
            }
            return structure.fields.map { IterationValue(field = it) }
        }
        if (property == "annotations") {
            val field = bindings[argumentName]?.field
            if (field != null) return field.annotations.map { IterationValue(annotation = it) }
        }
        diagnostics += diagnostic(
            nestedCode,
            "nested reflected-field loops support type `.fields` and a bound field's `.annotations` collection",
            iterable?.span ?: iterableField?.span ?: loop.span
        )
        return null
    }

    private fun expandBody(
        body: CPlusSyntaxNode,
        bindings: Map<String, IterationValue>,
        structures: Map<String, ReflectedStructure>,
        source: MappedText,
        text: String,
        diagnostics: MutableList<CPlusLoweringDiagnostic>,
        budget: ExpansionBudget
    ): MappedText? {
        val bodyStart = body.span.startOffset + 1
        val bodyEnd = body.span.endOffset - 1
        val nestedLoops = directNestedLoops(body)
        val replacements = mutableListOf<Replacement>()

        descendants(body)
            .filter { node ->
                node.kind == "field_expression" &&
                    node.children.firstOrNull { it.fieldName == "argument" }
                        ?.let { textOf(it, text) } in bindings &&
                    nestedLoops.none { it.span.startOffset <= node.span.startOffset && node.span.endOffset <= it.span.endOffset }
            }
            .forEach { reference ->
                val argument = reference.children.first { it.fieldName == "argument" }
                val binding = bindings[textOf(argument, text)]
                val propertyNode = reference.children.firstOrNull { it.fieldName == "field" }
                val property = propertyNode?.let { textOf(it, text) }
                when {
                    binding?.field != null && property != null -> {
                        val field = binding.field
                        if (property !in REFLECTED_FIELD_PROPERTIES) {
                            diagnostics += diagnostic(
                                "CPLUS_COMPTIME_REFLECTION_PROPERTY",
                                "field reflection supports `.name`, `.type`, `.annotations`, `.annotationsText`, `.flag`, `.width`, `.offset`, `.size`, and `.align`",
                                propertyNode?.span ?: reference.span
                            )
                        } else if (property in LAYOUT_FIELD_PROPERTIES && field.layout == null) {
                            diagnostics += diagnostic(
                                "CPLUS_COMPTIME_REFLECTION_LAYOUT",
                                "${field.name.ifBlank { "<anonymous>" }}.$property is unavailable for target ABI $targetArch-$targetOs",
                                propertyNode?.span ?: reference.span
                            )
                        } else {
                            val (value, originOffset) = fieldProperty(field, property)
                            replacements += Replacement(
                                reference.span.startOffset,
                                reference.span.endOffset,
                                generated(value, source, originOffset)
                            )
                        }
                    }
                    binding?.annotation != null && property != null -> {
                        val annotation = binding.annotation
                        if (property !in REFLECTED_ANNOTATION_PROPERTIES) {
                            diagnostics += diagnostic(
                                "CPLUS_COMPTIME_ANNOTATION_PROPERTY",
                                "annotation reflection supports only `.name`",
                                propertyNode?.span ?: reference.span
                            )
                        } else {
                            replacements += Replacement(
                                reference.span.startOffset,
                                reference.span.endOffset,
                                generated(quote(annotation.name), source, annotation.span.startOffset)
                            )
                        }
                    }
                    else -> diagnostics += diagnostic(
                        "CPLUS_COMPTIME_REFLECTION_PROPERTY",
                        "reflection member access requires a bound field or annotation value",
                        propertyNode?.span ?: reference.span
                    )
                }
            }

        val annotationBindings = bindings
            .filterValues { it.annotation != null }
            .mapValues { (_, value) -> value.annotation!! }
        if (annotationBindings.isNotEmpty()) {
            descendants(body)
                .filter { node ->
                    node.children.isEmpty() && node.kind in setOf("identifier", "type_identifier") &&
                        textOf(node, text) in annotationBindings &&
                        nestedLoops.none { it.span.startOffset <= node.span.startOffset && node.span.endOffset <= it.span.endOffset }
                }
                .forEach { identifier ->
                    val annotation = annotationBindings[textOf(identifier, text)] ?: return@forEach
                    replacements += Replacement(
                        identifier.span.startOffset,
                        identifier.span.endOffset,
                        generated(quote(annotation.name), source, annotation.span.startOffset)
                    )
                }
        }

        nestedLoops.forEach { nestedLoop ->
            val nestedExpansion = expandLoop(
                nestedLoop,
                bindings,
                structures,
                source,
                text,
                diagnostics,
                nested = true,
                budget = budget
            )
            if (nestedExpansion != null) {
                replacements += Replacement(nestedLoop.span.startOffset, nestedLoop.span.endOffset, nestedExpansion)
            }
        }
        if (diagnostics.isNotEmpty()) return null

        val output = MappedTextBuilder()
        appendReplacedRange(output, source, bodyStart, bodyEnd, replacements)
        return output.build()
    }

    private fun fieldProperty(field: ReflectedField, property: String): Pair<String, Int> = when (property) {
        "name" -> quote(field.name) to field.nameSpan.startOffset
        "type" -> field.type to field.typeSpan.startOffset
        "annotations", "annotationsText" -> quote(field.annotations.joinToString(" ") { it.name }) to
            (field.annotationSpan?.startOffset ?: field.typeSpan.startOffset)
        "flag" -> (if (field.bitWidth == null) "0" else "1") to
            (field.bitWidthSpan?.startOffset ?: field.typeSpan.startOffset)
        "width" -> (field.bitWidth ?: "0") to
            (field.bitWidthSpan?.startOffset ?: field.typeSpan.startOffset)
        "offset" -> field.layout!!.offset.toString() to field.nameSpan.startOffset
        "size" -> field.layout!!.size.toString() to field.nameSpan.startOffset
        "align" -> field.layout!!.alignment.toString() to field.nameSpan.startOffset
        else -> error("unsupported reflected property $property")
    }

    private fun reflectedStructs(
        root: CPlusSyntaxNode,
        text: String,
        layouts: Map<String, TreeSitterAbiAggregateLayout>
    ): Map<String, ReflectedStructure> {
        val result = linkedMapOf<String, ReflectedStructure>()
        val activeNodes = activeDescendants(root).toList()
        val typeDefinitions = activeNodes.filter { it.kind == "type_definition" }
        activeNodes.filter { it.kind == "struct_specifier" }.forEach { structure ->
            val body = structure.children.firstOrNull { it.fieldName == "body" }
                ?: structure.children.firstOrNull { it.kind == "field_declaration_list" }
                ?: return@forEach
            val declarations = body.children.filter { it.kind == "field_declaration" }
            val fields = mutableListOf<ReflectedField>()
            var invalidFieldSpan: cplus.SourceSpan? = null
            declarations.forEach { fieldDeclaration ->
                val parsedFields = reflectedFields(fieldDeclaration, text)
                if (parsedFields == null) invalidFieldSpan = invalidFieldSpan ?: fieldDeclaration.span
                else fields += parsedFields
            }
            val tag = structure.children.firstOrNull { it.fieldName == "name" }
                ?.let { textOf(it, text) }
            val typedef = typeDefinitions.firstOrNull {
                it.kind == "type_definition" &&
                    it.span.startOffset <= structure.span.startOffset && it.span.endOffset >= structure.span.endOffset
            }
            val alias = typedef?.children?.firstOrNull { it.fieldName == "declarator" }
                ?.let { descendants(it).firstOrNull { child -> child.kind in TYPE_NAME_NODES } }
                ?.let { textOf(it, text) }
            val layout = (tag?.let { layouts[it] } ?: alias?.let { layouts[it] })
            val fieldsWithLayout = fields.map { field ->
                field.copy(layout = layout?.fields?.firstOrNull { it.name == field.name })
            }
            val metadata = ReflectedStructure(fieldsWithLayout, invalidFieldSpan)
            if (tag != null) result[tag] = metadata
            if (alias != null) result[alias] = metadata
        }
        return result
    }

    private fun reflectedFields(declaration: CPlusSyntaxNode, text: String): List<ReflectedField>? {
        val declarators = declaration.children.filter {
            it.fieldName == "declarator" && it.kind != "bitfield_clause"
        }
        val annotations = descendants(declaration).filter { it.kind == "cplus_result_annotation" }.toList()
        val annotationValues = annotations.map { ReflectedAnnotation(textOf(it, text), it.span) }
        if (declarators.isEmpty()) {
            val bitfield = descendants(declaration).firstOrNull { it.kind == "bitfield_clause" } ?: return emptyList()
            val widthNode = bitfield.children.firstOrNull { it.named } ?: return null
            val width = textOf(widthNode, text)
            val widthSpan = widthNode.span
            val baseType = removeSpans(
                text.substring(declaration.span.startOffset, bitfield.span.startOffset),
                declaration.span.startOffset,
                annotations
            ).replace(Regex("\\s+"), " ").trim()
            if (baseType.isBlank()) return null
            val typeNode = declaration.children.firstOrNull { it.fieldName == "type" }
                ?: declaration.children.firstOrNull {
                    it.kind !in setOf("bitfield_clause", "cplus_result_annotation", "attribute_specifier")
                }
            return listOf(
                ReflectedField(
                    name = "",
                    type = baseType,
                    annotations = annotationValues,
                    nameSpan = bitfield.span,
                    typeSpan = typeNode?.span ?: declaration.span,
                    annotationSpan = annotations.firstOrNull()?.span,
                    bitWidth = width,
                    bitWidthSpan = widthSpan
                )
            )
        }
        val basePrefix = text.substring(declaration.span.startOffset, declarators.first().span.startOffset)
        val baseType = removeSpans(basePrefix, declaration.span.startOffset, annotations)
            .replace(Regex("\\s+"), " ").trim()
        val bitfieldClauses = descendants(declaration).filter { it.kind == "bitfield_clause" }.toList()
        return declarators.mapIndexed { index, declarator ->
            val name = descendants(declarator).firstOrNull { it.kind in FIELD_NAME_NODES }
                ?: return null
            if (baseType.isBlank() || name.span.startOffset < declarator.span.startOffset || name.span.endOffset > declarator.span.endOffset) {
                return null
            }
            // Remove only the declared identifier. The remaining parsed C declarator is
            // precisely the abstract declarator needed to describe the field's type:
            // `items[4]` -> `[4]`, `*items[4]` -> `*[4]`, `(*callback)(int)` -> `(*)(int)`.
            val abstractDeclarator = buildString {
                append(text, declarator.span.startOffset, name.span.startOffset)
                append(text, name.span.endOffset, declarator.span.endOffset)
            }.replace(Regex("\\s+"), " ").trim()
            val type = (baseType + if (abstractDeclarator.isEmpty()) "" else " $abstractDeclarator")
                .replace(Regex("\\s+"), " ").trim()
            val nextDeclaratorStart = declarators.getOrNull(index + 1)?.span?.startOffset ?: declaration.span.endOffset
            val bitfield = bitfieldClauses.firstOrNull {
                it.span.startOffset >= declarator.span.endOffset && it.span.startOffset < nextDeclaratorStart
            }
            val bitWidth = bitfield?.children?.firstOrNull { it.named }
            ReflectedField(
                name = textOf(name, text),
                type = type,
                annotations = annotationValues,
                nameSpan = name.span,
                typeSpan = declaration.children.firstOrNull { it.fieldName == "type" }?.span
                    ?: declaration.children.firstOrNull {
                        it.fieldName != "declarator" && it.kind !in setOf("cplus_result_annotation", "attribute_specifier")
                    }?.span
                    ?: declaration.span,
                annotationSpan = annotations.firstOrNull()?.span,
                bitWidth = bitWidth?.let { textOf(it, text) },
                bitWidthSpan = bitWidth?.span
            )
        }
    }

    private fun removeSpans(value: String, absoluteStart: Int, spans: List<CPlusSyntaxNode>): String {
        val output = StringBuilder()
        var cursor = absoluteStart
        spans.sortedBy { it.span.startOffset }.forEach { node ->
            if (node.span.startOffset >= cursor && node.span.endOffset <= absoluteStart + value.length) {
                output.append(value, cursor - absoluteStart, node.span.startOffset - absoluteStart)
                cursor = node.span.endOffset
            }
        }
        output.append(value, cursor - absoluteStart, value.length)
        return output.toString()
    }

    private fun directNestedLoops(body: CPlusSyntaxNode): List<CPlusSyntaxNode> {
        val allLoops = descendants(body).filter { it !== body && it.kind == LOOP_KIND }.toList()
        return allLoops.filter { candidate ->
            allLoops.none { other ->
                other !== candidate && other.span.startOffset < candidate.span.startOffset &&
                    other.span.endOffset > candidate.span.endOffset
            }
        }.sortedBy { it.span.startOffset }
    }

    private fun appendReplacedRange(
        output: MappedTextBuilder,
        source: MappedText,
        start: Int,
        end: Int,
        replacements: List<Replacement>
    ) {
        var cursor = start
        replacements.sortedBy { it.start }.forEach { replacement ->
            if (replacement.start < cursor || replacement.end > end) return@forEach
            output.append(source, cursor, replacement.start)
            output.append(replacement.content)
            cursor = replacement.end
        }
        output.append(source, cursor, end)
    }

    private fun generated(value: String, source: MappedText, originOffset: Int): MappedText =
        MappedTextBuilder().apply { appendGenerated(value, source.originAt(originOffset)) }.build()

    private fun textOf(node: CPlusSyntaxNode, text: String): String = text.substring(node.span.startOffset, node.span.endOffset)

    private fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> = sequence {
        yield(node)
        node.children.forEach { yieldAll(descendants(it)) }
    }

    private fun activeDescendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> = sequence {
        yield(node)
        if (node.kind !in GENERATOR_DEFINITION_NODES) node.children.forEach { yieldAll(activeDescendants(it)) }
    }

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun diagnostic(code: String, message: String, span: cplus.SourceSpan) =
        CPlusLoweringDiagnostic(code, message, span)

    private data class ReflectedField(
        val name: String,
        val type: String,
        val annotations: List<ReflectedAnnotation>,
        val nameSpan: cplus.SourceSpan,
        val typeSpan: cplus.SourceSpan,
        val annotationSpan: cplus.SourceSpan?,
        val bitWidth: String? = null,
        val bitWidthSpan: cplus.SourceSpan? = null,
        val layout: TreeSitterAbiFieldLayout? = null
    )

    private data class ReflectedAnnotation(val name: String, val span: cplus.SourceSpan)

    private data class IterationValue(
        val field: ReflectedField? = null,
        val annotation: ReflectedAnnotation? = null
    )

    private class ExpansionBudget(private var remaining: Int = MAX_TOTAL_EXPANSION_ITEMS) {
        fun consume(): Boolean {
            if (remaining <= 0) return false
            remaining--
            return true
        }
    }

    private data class ReflectedStructure(
        val fields: List<ReflectedField>,
        val invalidFieldSpan: cplus.SourceSpan?
    )

    private data class Replacement(val start: Int, val end: Int, val content: MappedText)

    private companion object {
        const val LOOP_KIND = "cplus_comptime_for"
        const val MAX_REFLECTED_FIELDS = 1024
        const val MAX_TOTAL_EXPANSION_ITEMS = 65536
        val GENERATOR_DEFINITION_NODES = setOf(
            "cplus_comptime_function_definition", "cplus_legacy_type_generator", "cplus_legacy_function_generator"
        )
        val TYPE_NAME_NODES = setOf("identifier", "type_identifier", "primitive_type")
        val FIELD_NAME_NODES = setOf("identifier", "field_identifier", "type_identifier")
        val REFLECTED_FIELD_PROPERTIES = setOf(
            "name", "type", "annotations", "annotationsText", "flag", "width", "offset", "size", "align"
        )
        val REFLECTED_ANNOTATION_PROPERTIES = setOf("name")
        val LAYOUT_FIELD_PROPERTIES = setOf("offset", "size", "align")
        val PREPROCESSOR_CONTAINERS = setOf(
            "preproc_if", "preproc_else", "preproc_elif", "preproc_ifdef", "preproc_ifndef",
            "preproc_elifdef", "preproc_elifndef"
        )
    }
}
