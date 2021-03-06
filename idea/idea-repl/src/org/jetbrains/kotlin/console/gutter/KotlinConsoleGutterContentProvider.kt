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

package org.jetbrains.kotlin.console.gutter

import com.intellij.execution.console.BasicGutterContentProvider
import com.intellij.openapi.editor.Editor

public class KotlinConsoleGutterContentProvider : BasicGutterContentProvider() {
    /**
     *  This method overriding is needed to prevent [BasicGutterContentProvider] from adding some strange unicode
     *  symbols of zero width and to ease range highlighting.
     */
    override fun beforeEvaluate(_: Editor) = Unit
}