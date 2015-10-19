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

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry

public class InsertCurlyBracesToTemplateIntention : JetSelfTargetingOffsetIndependentIntention<KtSimpleNameStringTemplateEntry> (
        javaClass(), "Insert curly braces around variable"), LowPriorityAction {

    override fun isApplicableTo(element: KtSimpleNameStringTemplateEntry): Boolean = true

    override fun applyTo(element: KtSimpleNameStringTemplateEntry, editor: Editor) {
        val expression = element.getExpression() ?: return
        element.replace(KtPsiFactory(element).createBlockStringTemplateEntry(expression))
    }
}
