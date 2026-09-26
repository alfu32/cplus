package cplus.intellij

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import javax.swing.Icon

class CPlusStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile) = object : TreeBasedStructureViewBuilder() {
        override fun createStructureViewModel(editor: Editor?): StructureViewModel = CPlusStructureViewModel(psiFile)
    }
}

private class CPlusStructureViewModel(file: PsiFile) :
    StructureViewModelBase(file, CPlusStructureElement(file, null)),
    StructureViewModel.ElementInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        (element as? CPlusStructureElement)?.hasChildren() == true

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        (element as? CPlusStructureElement)?.hasChildren() == false
}

private class CPlusStructureElement(
    private val file: PsiFile,
    val symbol: CPlusParserSymbol?
) : StructureViewTreeElement {
    override fun getValue(): Any = symbol ?: file

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = symbol?.name ?: file.name
        override fun getLocationString(): String? = symbol?.owner?.let { "in $it" }
            ?: symbol?.detail?.takeIf { it != symbol.name }
        override fun getIcon(open: Boolean): Icon? = null
    }

    override fun getChildren(): Array<TreeElement> {
        val children = symbol?.children ?: run {
            val path = file.virtualFile?.path ?: return TreeElement.EMPTY_ARRAY
            CPlusParserTreeCache.symbols(path, file.text).orEmpty()
        }
        return children.map { CPlusStructureElement(file, it) }.toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) {
        val target = symbol ?: return
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, target.startOffset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = symbol != null && file.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()

    fun hasChildren(): Boolean = if (symbol != null) {
        symbol.children.isNotEmpty()
    } else {
        val path = file.virtualFile?.path ?: return false
        !CPlusParserTreeCache.symbols(path, file.text).isNullOrEmpty()
    }
}
