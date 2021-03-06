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

@file:JvmName("ExtensionUtils")

package org.jetbrains.kotlin.idea.util

import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.psi.JetPsiUtil
import org.jetbrains.kotlin.psi.JetThisExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.calls.smartcasts.SmartCastManager
import org.jetbrains.kotlin.resolve.scopes.JetScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.ThisReceiver
import org.jetbrains.kotlin.types.JetType
import org.jetbrains.kotlin.types.typeUtil.TypeNullability
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlin.types.typeUtil.nullability

public fun CallableDescriptor.substituteExtensionIfCallable(
        receivers: Collection<ReceiverValue>,
        context: BindingContext,
        dataFlowInfo: DataFlowInfo,
        callType: CallType<*>,
        containingDeclarationOrModule: DeclarationDescriptor
): Collection<CallableDescriptor> {
    val sequence = receivers.asSequence().flatMap { substituteExtensionIfCallable(it, callType, context, dataFlowInfo, containingDeclarationOrModule).asSequence() }
    if (getTypeParameters().isEmpty()) { // optimization for non-generic callables
        return sequence.firstOrNull()?.let { listOf(it) } ?: listOf()
    }
    else {
        return sequence.toList()
    }
}

public fun CallableDescriptor.substituteExtensionIfCallableWithImplicitReceiver(
        scope: JetScope,
        context: BindingContext,
        dataFlowInfo: DataFlowInfo
): Collection<CallableDescriptor> {
    val receiverValues = scope.getImplicitReceiversWithInstance().map { it.getValue() }
    return substituteExtensionIfCallable(receiverValues, context, dataFlowInfo, CallType.DEFAULT, scope.getContainingDeclaration())
}

public fun CallableDescriptor.substituteExtensionIfCallable(
        receiver: ReceiverValue,
        callType: CallType<*>,
        bindingContext: BindingContext,
        dataFlowInfo: DataFlowInfo,
        containingDeclarationOrModule: DeclarationDescriptor
): Collection<CallableDescriptor> {
    if (!receiver.exists()) return listOf()

    var types = SmartCastManager().getSmartCastVariants(receiver, bindingContext, containingDeclarationOrModule, dataFlowInfo)
    return substituteExtensionIfCallable(types, callType)
}

public fun CallableDescriptor.substituteExtensionIfCallable(
        receiverTypes: Collection<JetType>,
        callType: CallType<*>
): Collection<CallableDescriptor> {
    if (!callType.descriptorKindFilter.accepts(this)) return listOf()

    var types = receiverTypes.asSequence()
    if (callType == CallType.SAFE) {
        types = types.map { it.makeNotNullable() }
    }

    val extensionReceiverType = fuzzyExtensionReceiverType()!!
    val substitutors = types
            .map {
                var substitutor = extensionReceiverType.checkIsSuperTypeOf(it)
                // check if we may fail due to receiver expression being nullable
                if (substitutor == null && it.nullability() == TypeNullability.NULLABLE && extensionReceiverType.nullability() == TypeNullability.NOT_NULL) {
                    substitutor = extensionReceiverType.checkIsSuperTypeOf(it.makeNotNullable())
                }
                substitutor
            }
            .filterNotNull()
    if (getTypeParameters().isEmpty()) { // optimization for non-generic callables
        return if (substitutors.any()) listOf(this) else listOf()
    }
    else {
        return substitutors.map { substitute(it)!! }.toList()
    }
}

public fun ReceiverValue.getThisReceiverOwner(bindingContext: BindingContext): DeclarationDescriptor? {
    return when (this) {
        is ExpressionReceiver -> {
            val thisRef = (JetPsiUtil.deparenthesize(this.getExpression()) as? JetThisExpression)?.getInstanceReference() ?: return null
            bindingContext[BindingContext.REFERENCE_TARGET, thisRef]
        }

        is ThisReceiver -> this.getDeclarationDescriptor()

        else -> null
    }
}
