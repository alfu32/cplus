package cplus.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.FileViewProvider

class CPlusParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = CPlusLexer()

    override fun createParser(project: Project?): PsiParser = object : PsiParser {
        override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
            val marker = builder.mark()
            while (!builder.eof()) {
                builder.advanceLexer()
            }
            marker.done(root)
            return builder.treeBuilt
        }
    }

    override fun getFileNodeType(): IFileElementType = CPlusTokenTypes.FILE

    override fun createFile(viewProvider: FileViewProvider): PsiFile = CPlusPsiFile(viewProvider)

    override fun createElement(node: ASTNode): PsiElement = CPlusPsiElement(node)

    override fun getCommentTokens(): TokenSet = TokenSet.create(CPlusTokenTypes.COMMENT)

    override fun getStringLiteralElements(): TokenSet = TokenSet.create(CPlusTokenTypes.STRING)

    override fun spaceExistenceTypeBetweenTokens(left: ASTNode?, right: ASTNode?): ParserDefinition.SpaceRequirements =
        ParserDefinition.SpaceRequirements.MAY
}

private class CPlusPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, CPlusLanguage) {
    override fun getFileType() = CPlusFileType.INSTANCE
    override fun toString(): String = "C-plus file"
}

private class CPlusPsiElement(node: ASTNode) : com.intellij.extapi.psi.ASTWrapperPsiElement(node)
