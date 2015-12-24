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

package org.jetbrains.kotlin.codegen

import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.KtNodeTypes.*

class ConstPropertiesChecker {
    fun containsKotlinConst(expression: KtExpression): Boolean {
        var result = false
        expression.accept(object : KtTreeVisitorVoid() {
            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                if (expression is KtNameReferenceExpression) {
                    if (expression.elementType == REFERENCE_EXPRESSION) {
                        result = true
                    }
                }
            }
        })

        return result
    }
}
