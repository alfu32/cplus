package cplus.parser

import cplus.CPlusSyntaxNode

/**
 * The target ABI facts that the comptime reflection prototype can prove from
 * syntax alone.  The model is deliberately shared by scalar type reflection
 * and reflected-field iteration so the two APIs cannot silently disagree.
 */
internal data class TreeSitterAbiLayout(
    val size: Long,
    val alignment: Long
)

internal data class TreeSitterAbiFieldLayout(
    val name: String,
    val offset: Long,
    val size: Long,
    val alignment: Long
)

internal data class TreeSitterAbiAggregateLayout(
    val size: Long,
    val alignment: Long,
    val fields: List<TreeSitterAbiFieldLayout>
)

/**
 * Computes the intentionally small, fail-closed aggregate layout subset used
 * by the AST comptime prototype.  This covers ordinary standard-layout C
 * structs with scalar, pointer, nested-struct, and fixed-array fields on the
 * supported 64-bit targets.  Compiler-specific packing, bitfields, unions,
 * flexible arrays, and incomplete field types remain unsupported until the
 * target ABI oracle is introduced.
 */
internal fun computeTreeSitterAbiAggregateLayouts(
    root: CPlusSyntaxNode,
    text: String,
    targetOs: String,
    targetArch: String
): Map<String, TreeSitterAbiAggregateLayout> {
    val normalizedOs = cplus.CPlusTarget.normalizeOs(targetOs)
    val normalizedArch = cplus.CPlusTarget.normalizeArch(targetArch)
    if (normalizedArch !in SUPPORTED_LAYOUT_ARCHITECTURES || normalizedOs !in SUPPORTED_LAYOUT_OPERATING_SYSTEMS) {
        return emptyMap()
    }

    fun descendants(node: CPlusSyntaxNode): Sequence<CPlusSyntaxNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

    fun normalizeType(rawType: String): String = rawType
        .replace(Regex("\\b(const|volatile|restrict|static|extern|register|auto|inline)\\b"), " ")
        .replace(Regex("\\bstruct\\s+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun primitiveAliases(): Map<String, String> = descendants(root).asSequence()
        .filter { it.kind == "type_definition" }
        .mapNotNull { typedef ->
            val type = typedef.children.firstOrNull { it.fieldName == "type" } ?: return@mapNotNull null
            val declarator = typedef.children.firstOrNull { it.fieldName == "declarator" } ?: return@mapNotNull null
            val alias = descendants(declarator)
                .firstOrNull { it.kind in setOf("identifier", "type_identifier") }
                ?: return@mapNotNull null
            if (descendants(declarator).any { it.kind in setOf("array_declarator", "function_declarator") }) {
                return@mapNotNull null
            }
            val target = text.substring(type.span.startOffset, type.span.endOffset)
                .replace(Regex("\\b(const|volatile|restrict)\\b"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val pointerDepth = descendants(declarator).count { it.kind == "pointer_declarator" }
            text.substring(alias.span.startOffset, alias.span.endOffset) to target + "*".repeat(pointerDepth)
        }
        .toMap()

    val primitiveTypedefAliases = primitiveAliases()
    val aggregateAliases = descendants(root).asSequence()
        .filter { it.kind == "type_definition" }
        .mapNotNull { typedef ->
            val type = typedef.children.firstOrNull { it.fieldName == "type" } ?: return@mapNotNull null
            val composite = descendants(type).firstOrNull { it.kind == "struct_specifier" }
                ?: return@mapNotNull null
            val tag = composite.children.firstOrNull { it.fieldName == "name" }
                ?.let { text.substring(it.span.startOffset, it.span.endOffset) }
                ?: return@mapNotNull null
            val declarator = typedef.children.firstOrNull { it.fieldName == "declarator" }
                ?: return@mapNotNull null
            val alias = descendants(declarator)
                .firstOrNull { it.kind in setOf("identifier", "type_identifier") }
                ?.let { text.substring(it.span.startOffset, it.span.endOffset) }
                ?: return@mapNotNull null
            alias to tag
        }
        .toMap()

    data class FieldSpec(val name: String, val type: String)
    data class AggregateSpec(val fields: List<FieldSpec>, val invalid: Boolean)

    val specs = linkedMapOf<String, AggregateSpec>()
    descendants(root).filter { it.kind == "struct_specifier" }.forEach { structure ->
        val tag = structure.children.firstOrNull { it.fieldName == "name" }
            ?.let { text.substring(it.span.startOffset, it.span.endOffset) }
            ?: return@forEach
        val body = structure.children.firstOrNull { it.kind == "field_declaration_list" }
        val declarations = body?.children?.filter { it.kind == "field_declaration" }.orEmpty()
        var invalid = body == null || structure.children.any { child ->
            child.kind == "attribute_specifier" && text.substring(child.span.startOffset, child.span.endOffset)
                .contains(Regex("packed|aligned"))
        }
        val fields = mutableListOf<FieldSpec>()
        declarations.forEach { declaration ->
            val declarators = declaration.children.filter { it.fieldName == "declarator" }
            val typeNode = declaration.children.firstOrNull { it.fieldName == "type" }
            if (declarators.isEmpty() || typeNode == null || descendants(declaration).any { it.kind == "bitfield_clause" }) {
                invalid = true
                return@forEach
            }
            val baseType = text.substring(typeNode.span.startOffset, typeNode.span.endOffset)
            declarators.forEach { declarator ->
                val nameNode = descendants(declarator).firstOrNull {
                    it.kind in setOf("field_identifier", "identifier", "type_identifier")
                }
                if (nameNode == null) {
                    invalid = true
                    return@forEach
                }
                val abstractDeclarator = buildString {
                    append(text, declarator.span.startOffset, nameNode.span.startOffset)
                    append(text, nameNode.span.endOffset, declarator.span.endOffset)
                }
                fields += FieldSpec(
                    name = text.substring(nameNode.span.startOffset, nameNode.span.endOffset),
                    type = normalizeType("$baseType $abstractDeclarator")
                )
            }
        }
        specs[tag] = AggregateSpec(fields, invalid)
    }

    fun primitiveLayout(type: String): TreeSitterAbiLayout? {
        val canonical = when (type.replace(Regex("\\s+"), " ").trim()) {
            "char", "signed char", "unsigned char" -> "char"
            "_Bool", "bool" -> "bool"
            "short", "short int", "signed short", "signed short int", "unsigned short", "unsigned short int" -> "short"
            "int", "signed", "signed int", "unsigned", "unsigned int" -> "int"
            "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int" -> "long"
            "long long", "long long int", "signed long long", "signed long long int", "unsigned long long", "unsigned long long int" -> "long long"
            "float" -> "float"
            "double" -> "double"
            else -> return null
        }
        val size = when (canonical) {
            "char", "bool" -> 1L
            "short" -> 2L
            "int", "float" -> 4L
            "long" -> if (normalizedOs == "windows") 4L else 8L
            "long long", "double" -> 8L
            else -> return null
        }
        return TreeSitterAbiLayout(size, size)
    }

    fun align(value: Long, alignment: Long): Long {
        if (alignment <= 1L) return value
        val remainder = value % alignment
        return if (remainder == 0L) value else Math.addExact(value, alignment - remainder)
    }

    val cache = linkedMapOf<String, TreeSitterAbiAggregateLayout>()
    val active = mutableSetOf<String>()
    lateinit var layoutFor: (String, Set<String>) -> TreeSitterAbiAggregateLayout?

    fun fieldLayout(rawType: String, stack: Set<String>): TreeSitterAbiLayout? {
        var type = normalizeType(rawType)
        val arrayLengths = Regex("\\[\\s*([0-9]+)\\s*\\]")
            .findAll(type)
            .map { it.groupValues[1].toLongOrNull() }
            .toList()
        if (type.contains("[]") || arrayLengths.any { it == null || it <= 0L }) return null
        type = type.replace(Regex("\\[[^]]*\\]"), "").trim()
        var resolved = type
        val visited = mutableSetOf<String>()
        while (visited.add(resolved)) {
            // Aggregate aliases must win: primitiveTypedefAliases also sees the
            // `struct ...` type node in a typedef declaration.
            resolved = aggregateAliases[resolved] ?: primitiveTypedefAliases[resolved] ?: break
        }
        val element = if ('*' in resolved) {
            TreeSitterAbiLayout(8L, 8L).takeIf { normalizedArch in setOf("x86_64", "arm64") }
        } else {
            primitiveLayout(resolved) ?: layoutFor(resolved, stack)?.let {
                TreeSitterAbiLayout(it.size, it.alignment)
            }
        } ?: return null
        val multiplier = try {
            arrayLengths.fold(1L) { result, length -> Math.multiplyExact(result, length ?: return null) }
        } catch (_: ArithmeticException) {
            return null
        }
        return try {
            TreeSitterAbiLayout(Math.multiplyExact(element.size, multiplier), element.alignment)
        } catch (_: ArithmeticException) {
            null
        }
    }

    layoutFor = { rawName, stack ->
        val name = aggregateAliases[rawName] ?: rawName
        cache[name] ?: run {
            val spec = specs[name] ?: return@run null
            if (spec.invalid || name in stack || !active.add(name)) return@run null
            try {
                var offset = 0L
                var alignment = 1L
                val fields = mutableListOf<TreeSitterAbiFieldLayout>()
                spec.fields.forEach { field ->
                    val fieldLayout = fieldLayout(field.type, stack + name) ?: return@run null
                    offset = align(offset, fieldLayout.alignment)
                    fields += TreeSitterAbiFieldLayout(field.name, offset, fieldLayout.size, fieldLayout.alignment)
                    offset = Math.addExact(offset, fieldLayout.size)
                    alignment = maxOf(alignment, fieldLayout.alignment)
                }
                val result = TreeSitterAbiAggregateLayout(align(offset, alignment), alignment, fields)
                cache[name] = result
                result
            } catch (_: ArithmeticException) {
                null
            } finally {
                active.remove(name)
            }
        }
    }

    specs.keys.forEach { name -> layoutFor(name, emptySet()) }
    return buildMap {
        cache.forEach { (name, layout) ->
            put(name, layout)
            aggregateAliases.filterValues { it == name }.keys.forEach { alias -> put(alias, layout) }
        }
    }
}

private val SUPPORTED_LAYOUT_ARCHITECTURES = setOf("x86_64", "arm64")
private val SUPPORTED_LAYOUT_OPERATING_SYSTEMS = setOf("linux", "windows", "macos")
