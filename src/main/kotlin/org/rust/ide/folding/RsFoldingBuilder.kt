package org.rust.ide.folding

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.FoldingGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.core.leftSiblings
import org.rust.lang.core.parser.RustParserDefinition.Companion.BLOCK_COMMENT
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.RsElementTypes.LBRACE
import org.rust.lang.core.psi.RsElementTypes.RBRACE
import org.rust.lang.core.psi.ext.getNextNonCommentSibling
import org.rust.lang.core.psi.ext.getPrevNonCommentSibling
import org.rust.lang.core.rightSiblings
import java.util.*

class RsFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun getPlaceholderText(node: ASTNode): String =
        when {
            node.elementType == LBRACE -> " { "
            node.elementType == RBRACE -> " }"
            node.psi is PsiComment -> "/* ... */"
            else -> "{...}"
        }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<out FoldingDescriptor> {
        if (root !is RsFile) return emptyArray()

        val descriptors: MutableList<FoldingDescriptor> = ArrayList()
        val visitor = FoldingVisitor(descriptors)
        PsiTreeUtil.processElements(root) { it.accept(visitor); true }

        return descriptors.toTypedArray()
    }

    private class FoldingVisitor(private val descriptors: MutableList<FoldingDescriptor>) : RsVisitor() {

        override fun visitStructExprBody(o: RsStructExprBody) = fold(o)

        override fun visitEnumBody(o: RsEnumBody) = fold(o)

        override fun visitBlockFields(o: RsBlockFields) = fold(o)

        override fun visitBlock(o: RsBlock) {
            if (tryFoldBlockWhitespaces(o)) return
            fold(o)
        }

        override fun visitMatchBody(o: RsMatchBody) = fold(o)

        override fun visitUseGlobList(o: RsUseGlobList) = fold(o)

        override fun visitTraitItem(o: RsTraitItem) = foldBetween(o, o.lbrace, o.rbrace)

        override fun visitModItem(o: RsModItem) = foldBetween(o, o.lbrace, o.rbrace)

        override fun visitMacroArg(o: RsMacroArg) = foldBetween(o, o.lbrace, o.rbrace)

        override fun visitImplItem(o: RsImplItem) = foldBetween(o, o.lbrace, o.rbrace)

        override fun visitComment(comment: PsiComment) {
            if (comment.tokenType == BLOCK_COMMENT) {
                fold(comment)
            }
        }

        override fun visitStructItem(o: RsStructItem) {
            val blockFields = o.blockFields
            if (blockFields != null) {
                fold(blockFields)
            }
        }

        private fun fold(element: PsiElement) {
            descriptors += FoldingDescriptor(element.node, element.textRange)
        }

        private fun foldBetween(element: PsiElement, left: PsiElement?, right: PsiElement?) {
            if (left != null && right != null) {
                val range = TextRange(left.textOffset, right.textOffset + 1)
                descriptors += FoldingDescriptor(element.node, range)
            }
        }

        private fun tryFoldBlockWhitespaces(block: RsBlock): Boolean {
            if (!(block.parent is RsFunction && block.isSingleLine)) return false

            val lbrace = block.lbrace
            val rbrace = block.rbrace ?: return false

            val blockElement = lbrace.getNextNonCommentSibling()
            if (blockElement == null || blockElement != rbrace.getPrevNonCommentSibling()) return false
            if (blockElement.textContains('\n')) return false

            val doc = PsiDocumentManager.getInstance(block.project).getDocument(block.containingFile) ?: return false
            if (!(doc.areOnAdjacentLines(lbrace, blockElement) && doc.areOnAdjacentLines(blockElement, rbrace))) return false

            val leadingSpace = lbrace.nextSibling as? PsiWhiteSpace ?: return false
            val trailingSpace = rbrace.prevSibling as? PsiWhiteSpace ?: return false

            val leftEl = block.prevSibling as? PsiWhiteSpace ?: lbrace
            val range1 = TextRange(leftEl.textOffset, leadingSpace.textRange.endOffset)
            val range2 = TextRange(trailingSpace.textOffset, rbrace.textRange.endOffset)
            val group = FoldingGroup.newGroup("one-liner")
            descriptors += FoldingDescriptor(lbrace.node, range1, group)
            descriptors += FoldingDescriptor(rbrace.node, range2, group)

            return true
        }
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean =
        RsCodeFoldingSettings.instance.collapsibleOneLineMethods && node.elementType in COLLAPSED_BY_DEFAULT

    private companion object {
        val COLLAPSED_BY_DEFAULT = TokenSet.create(LBRACE, RBRACE)
    }
}

private fun Document.areOnAdjacentLines(first: PsiElement, second: PsiElement): Boolean =
    getLineNumber(first.textRange.endOffset) + 1 == getLineNumber(second.textRange.startOffset)

private val RsBlock.isSingleLine: Boolean get() {
    // remove all leading and trailing spaces before counting lines
    val startContents = lbrace.rightSiblings.dropWhile { it is PsiWhiteSpace }.firstOrNull() ?: return false
    if (startContents.node.elementType == RBRACE) return false
    val endContents = rbrace?.leftSiblings?.dropWhile { it is PsiWhiteSpace }?.firstOrNull() ?: return false

    val doc = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return false
    return doc.getLineNumber(startContents.textOffset) == doc.getLineNumber(endContents.textRange.endOffset)
}
