package org.rust.lang.core.completion

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import org.rust.ide.icons.RsIcons
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsCompositeElement
import org.rust.lang.core.psi.ext.RsMod
import org.rust.lang.core.psi.ext.parentOfType
import org.rust.lang.core.psi.ext.valueParameters
import org.rust.lang.core.types.RustTypificationEngine
import org.rust.lang.core.types.types.RustUnknownType

object CompletionEngine {
    const val KEYWORD_PRIORITY = 10.0
}

fun RsCompositeElement.createLookupElement(scopeName: String): LookupElement {
    val base = LookupElementBuilder.create(this, scopeName)
        .withIcon(if (this is RsFile) RsIcons.MODULE else getIcon(0))

    return when (this) {
        is RsMod -> if (scopeName == "self" || scopeName == "super") {
            base.withInsertHandler(AddSuffixInsertionHandler("::"))
                .withTailText("::")
        } else {
            base
        }

        is RsConstant -> base.withTypeText(typeReference?.text)
        is RsFieldDecl -> base.withTypeText(typeReference?.text)

        is RsFunction -> base
            .withTypeText(retType?.typeReference?.text ?: "()")
            .withTailText(valueParameterList?.text?.replace("\\s+".toRegex(), " ") ?: "()")
            .withInsertHandler handler@ { context: InsertionContext, _: LookupElement ->
                if (context.isInUseBlock) return@handler
                if (context.alreadyHasParens) return@handler
                context.document.insertString(context.selectionEndOffset, "()")
                EditorModificationUtil.moveCaretRelatively(context.editor, if (valueParameters.isEmpty()) 2 else 1)
            }

        is RsStructItem -> base
            .withTailText(when {
                blockFields != null -> " { ... }"
                tupleFields != null -> tupleFields!!.text
                else -> ""
            })

        is RsEnumVariant -> base
            .withTypeText(parentOfType<RsEnumItem>()?.name ?: "")
            .withTailText(when {
                blockFields != null -> " { ... }"
                tupleFields != null ->
                    tupleFields!!.tupleFieldDeclList
                        .map { it.typeReference.text }
                        .joinToString(prefix = "(", postfix = ")")
                else -> ""
            })
            .withInsertHandler handler@ { context, _ ->
                if (context.isInUseBlock) return@handler
                val (text, shift) = when {
                    tupleFields != null -> Pair("()", 1)
                    blockFields != null -> Pair(" {}", 2)
                    else -> return@handler
                }
                context.document.insertString(context.selectionEndOffset, text)
                EditorModificationUtil.moveCaretRelatively(context.editor, shift)
            }

        is RsPatBinding -> base
            .withTypeText(RustTypificationEngine.typify(this).let {
                when (it) {
                    is RustUnknownType -> ""
                    else -> it.toString()
                }
            })

        else -> base
    }
}

private val InsertionContext.isInUseBlock: Boolean
    get() = file.findElementAt(startOffset - 1)!!.parentOfType<RsUseItem>() != null

private val InsertionContext.alreadyHasParens: Boolean get() {
    val parent = file.findElementAt(startOffset)!!.parentOfType<RsExpr>()
    return (parent is RsMethodCallExpr) || parent?.parent is RsCallExpr
}
