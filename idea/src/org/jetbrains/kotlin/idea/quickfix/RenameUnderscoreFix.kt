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

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.refactoring.rename.RenameHandlerRegistry
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.psi.JetDeclaration
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType

class RenameUnderscoreFix(declaration: JetDeclaration) : JetIntentionAction<JetDeclaration>(declaration) {
    override fun invoke(project: Project, editor: Editor?, file: JetFile) {
        if (editor == null) return
        val dataContext = DataManager.getInstance().getDataContext(editor.component)
        val renameHandler = RenameHandlerRegistry.getInstance().getRenameHandler(dataContext)
        renameHandler?.invoke(project, arrayOf(element), dataContext);
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile): Boolean {
        return super.isAvailable(project, editor, file) && editor != null
    }

    override fun getText(): String = "Rename"
    override fun getFamilyName(): String = getText()

    companion object : JetSingleIntentionActionFactory() {
        override fun createAction(diagnostic: Diagnostic): IntentionAction? {
            val declaration = diagnostic.psiElement.getNonStrictParentOfType<JetDeclaration>() ?: return null
            if (diagnostic.psiElement == (declaration as? PsiNameIdentifierOwner)?.nameIdentifier) {
                return RenameUnderscoreFix(declaration)
            }
            return null
        }
    }
}