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

package org.jetbrains.kotlin.idea.refactoring.rename;

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameJavaMethodProcessor
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.asJava.KotlinLightMethod
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.psi.JetFunction
import org.jetbrains.kotlin.psi.JetNamedDeclaration
import org.jetbrains.kotlin.psi.JetNamedFunction
import org.jetbrains.kotlin.psi.JetSecondaryConstructor

public class RenameKotlinFunctionProcessor : RenameKotlinPsiProcessor() {
    private val javaMethodProcessorInstance = RenameJavaMethodProcessor()

    override fun canProcessElement(element: PsiElement): Boolean {
        return element is JetNamedFunction || (element is KotlinLightMethod && element.getOrigin() is JetNamedFunction)
    }

    override fun substituteElementToRename(element: PsiElement?, editor: Editor?): PsiElement?  {
        val wrappedMethod = wrapPsiMethod(element)
        if (wrappedMethod == null) {
            return element
        }

        // Use java dialog to ask we should rename function with the base element
        val substitutedJavaElement = javaMethodProcessorInstance.substituteElementToRename(wrappedMethod, editor)

        return when (substitutedJavaElement) {
            is KotlinLightMethod -> substitutedJavaElement.getOrigin() as? JetNamedFunction
            else -> substitutedJavaElement
        }
    }

    override fun prepareRenaming(element: PsiElement?, newName: String?, allRenames: MutableMap<PsiElement, String>, scope: SearchScope) {
        val psiMethod = wrapPsiMethod(element)
        if (psiMethod?.getContainingClass() != null) {
            javaMethodProcessorInstance.prepareRenaming(psiMethod, newName, allRenames, scope)
        }
    }

    override fun renameElement(element: PsiElement?, newName: String?, usages: Array<out UsageInfo>?, listener: RefactoringElementListener?) {
        super.renameElement(element, newName, usages, listener)

        (element?.unwrapped as? JetNamedDeclaration)?.let { dropOverrideKeywordIfNecessary(it) }
    }

    private fun wrapPsiMethod(element: PsiElement?): PsiMethod? = when (element) {
        is KotlinLightMethod -> element
        is JetNamedFunction, is JetSecondaryConstructor -> runReadAction {
            LightClassUtil.getLightClassMethod(element as JetFunction)
        }
        else -> throw IllegalStateException("Can't be for element $element there because of canProcessElement()")
    }
}
