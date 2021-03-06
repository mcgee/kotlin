/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.daemon.impl.quickfix.OrderEntryFix
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixActionRegistrarImpl
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.references.JetSimpleNameReference.ShorteningMode
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.JetElement
import org.jetbrains.kotlin.psi.JetSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedElement
import org.jetbrains.kotlin.psi.psiUtil.startOffset

public object KotlinAddOrderEntryActionFactory : JetIntentionActionsFactory() {
    override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction>? {
        val simpleExpression = diagnostic.psiElement as? JetSimpleNameExpression ?: return emptyList()
        val refElement = simpleExpression.getQualifiedElement()

        val reference = object: PsiReferenceBase<JetElement>(refElement) {
            override fun resolve() = null

            override fun getVariants() = PsiReference.EMPTY_ARRAY

            override fun getRangeInElement(): TextRange? {
                val offset = simpleExpression.startOffset - refElement.startOffset
                return TextRange(offset, offset + simpleExpression.textLength)
            }

            override fun getCanonicalText() = refElement.text

            override fun bindToElement(element: PsiElement): PsiElement {
                return simpleExpression.mainReference.bindToElement(element, ShorteningMode.FORCED_SHORTENING)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return OrderEntryFix.registerFixes(QuickFixActionRegistrarImpl(null), reference) as List<IntentionAction>? ?: emptyList()
    }
}
