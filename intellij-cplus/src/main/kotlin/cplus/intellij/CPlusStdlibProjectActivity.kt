package cplus.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem

/** Exposes the resolved C-plus stdlib as a read-only-looking IDE library. */
internal class CPlusStdlibProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val root = CPlusStdlibIndex.root(project) ?: return
        val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: return
        val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        attach(table, "C-plus standard library", listOf(virtualRoot))
        val moduleRoots = CPlusStdlibIndex.moduleRoots(project).mapNotNull {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
        }
        if (moduleRoots.isNotEmpty()) attach(table, "C-plus project modules", moduleRoots)
    }

    private fun attach(
        table: com.intellij.openapi.roots.libraries.LibraryTable,
        name: String,
        roots: List<com.intellij.openapi.vfs.VirtualFile>
    ) {
        val existing = table.libraries.firstOrNull { it.name == name }
        val library = existing ?: table.modifiableModel.createLibrary(name)
        val model = library.modifiableModel
        val current = model.getUrls(OrderRootType.SOURCES).toSet()
        roots.filterNot { it.url in current }.forEach { model.addRoot(it, OrderRootType.SOURCES) }
        if (existing == null || roots.any { it.url !in current }) model.commit() else model.dispose()
    }
}
