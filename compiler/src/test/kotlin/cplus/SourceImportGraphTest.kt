package cplus

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceImportGraphTest {
    @Test
    fun retainsDeterministicDependenciesAndDoesNotFlagDagAsCycle() {
        val graph = SourceImportGraph()
        val a = SourceId.named("/project/a.cp")
        val b = SourceId.named("/project/b.cp")
        val c = SourceId.named("/project/c.cp")
        val location = SourceFile("@import(\"b.cp\")", a.value).span(0, 15)

        assertNull(graph.add(SourceImportEdge(a, b, location)))
        assertNull(graph.add(SourceImportEdge(a, c, location)))
        assertNull(graph.add(SourceImportEdge(b, c, location)))
        assertEquals(listOf(b, c), graph.importsOf(a).map { it.imported })
        assertEquals(3, graph.edges().size)
        assertEquals(listOf(c, b, a), graph.dependencyOrder(listOf(a)))
    }

    @Test
    fun reportsClosedCycleWhenNewEdgeReachesItsImporter() {
        val graph = SourceImportGraph()
        val a = SourceId.named("a.cp")
        val b = SourceId.named("b.cp")
        val c = SourceId.named("c.cp")
        val location = SourceFile("", "a.cp").span(0, 0)
        graph.add(SourceImportEdge(a, b, location))
        graph.add(SourceImportEdge(b, c, location))

        val cycle = graph.add(SourceImportEdge(c, a, location))

        assertEquals(listOf(c, a, b, c), cycle)
        assertTrue(graph.edges().any { it.importer == c && it.imported == a })
        assertTrue(assertThrows(IllegalStateException::class.java) { graph.dependencyOrder() }.message.orEmpty().contains("a.cp"))
    }

    @Test
    fun comptimeImportsUseSharedSourceManagerAndExposeCanonicalEdges() {
        val directory = Files.createTempDirectory("cplus-import-graph")
        try {
            val imported = directory.resolve("constants.cp")
            val root = directory.resolve("main.cp")
            Files.writeString(imported, "comptime int imported_value = 73;\n")
            val source = "comptime import \"constants.cp\";\nint result = comptime imported_value;\n"
            val manager = SourceManager()
            val rootSnapshot = manager.open(SourceId.fromPath(root), source)

            val result = ComptimeCompiler(rootSnapshot.sourceFile, SilentCompilationLogger, sourceManager = manager).compile()

            assertEquals(1, result.imports.size)
            assertEquals(SourceId.fromPath(root), result.imports.single().importer)
            assertEquals(SourceId.fromPath(imported), result.imports.single().imported)
            assertTrue(result.runtime.text.contains("int result = 73;"))
            assertNotNull(manager.current(SourceId.fromPath(imported)))
        } finally {
            Files.deleteIfExists(directory.resolve("main.cp"))
            Files.deleteIfExists(directory.resolve("constants.cp"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun comptimeImportCycleReportsTheRootImportLocation() {
        val directory = Files.createTempDirectory("cplus-import-cycle")
        try {
            val root = directory.resolve("root.cp")
            val imported = directory.resolve("imported.cp")
            val rootText = "comptime import \"imported.cp\";\nint root_value;\n"
            Files.writeString(root, rootText)
            Files.writeString(imported, "comptime import \"root.cp\";\nint imported_value;\n")
            val sourceManager = SourceManager()
            val source = sourceManager.open(SourceId.fromPath(root), rootText)

            val error = assertThrows(CPlusSyntaxException::class.java) {
                ComptimeCompiler(source.sourceFile, SilentCompilationLogger, sourceManager = sourceManager).compile()
            }

            assertTrue(error.message.orEmpty().startsWith("comptime import cycle:"))
            assertEquals(SourceId.fromPath(root).value, error.sourceSpan?.file)
            assertEquals(rootText.indexOf("comptime import"), error.sourceSpan?.startOffset)
            assertNotNull(sourceManager.current(SourceId.fromPath(imported)))
        } finally {
            Files.deleteIfExists(directory.resolve("root.cp"))
            Files.deleteIfExists(directory.resolve("imported.cp"))
            Files.deleteIfExists(directory)
        }
    }
}
