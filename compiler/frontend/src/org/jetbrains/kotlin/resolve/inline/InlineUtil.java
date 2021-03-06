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

package org.jetbrains.kotlin.resolve.inline;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilPackage;
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMapping;
import org.jetbrains.kotlin.resolve.calls.model.ArgumentMatch;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.constants.ArrayValue;
import org.jetbrains.kotlin.resolve.constants.ConstantValue;
import org.jetbrains.kotlin.resolve.constants.EnumValue;

import static kotlin.CollectionsKt.firstOrNull;

public class InlineUtil {
    public static boolean isInlineLambdaParameter(@NotNull ParameterDescriptor valueParameterOrReceiver) {
        return !KotlinBuiltIns.isNoinline(valueParameterOrReceiver) &&
               KotlinBuiltIns.isExactFunctionOrExtensionFunctionType(valueParameterOrReceiver.getOriginal().getType());
    }

    public static boolean isInline(@Nullable DeclarationDescriptor descriptor) {
        return descriptor instanceof SimpleFunctionDescriptor && getInlineStrategy(descriptor).isInline();
    }

    @NotNull
    public static InlineStrategy getInlineStrategy(@NotNull DeclarationDescriptor descriptor) {
        AnnotationDescriptor annotation = descriptor.getAnnotations().findAnnotation(KotlinBuiltIns.FQ_NAMES.inline);
        if (annotation == null) {
            return InlineStrategy.NOT_INLINE;
        }
        ConstantValue<?> argument = firstOrNull(annotation.getAllValueArguments().values());
        if (argument == null) {
            return InlineStrategy.AS_FUNCTION;
        }
        assert argument instanceof EnumValue : "Inline annotation parameter should be enum entry but was: " + argument;
        return InlineStrategy.valueOf(((EnumValue) argument).getValue().getName().asString());
    }

    public static boolean hasOnlyLocalReturn(@NotNull ValueParameterDescriptor descriptor) {
        return descriptor.getAnnotations().findAnnotation(KotlinBuiltIns.FQ_NAMES.crossinline) != null;
    }

    public static boolean checkNonLocalReturnUsage(
            @NotNull DeclarationDescriptor fromFunction,
            @NotNull JetExpression startExpression,
            @NotNull BindingTrace trace
    ) {
        PsiElement containingFunction = PsiTreeUtil.getParentOfType(startExpression, JetClassOrObject.class, JetDeclarationWithBody.class);
        if (containingFunction == null) {
            return false;
        }

        DeclarationDescriptor containingFunctionDescriptor = trace.get(BindingContext.DECLARATION_TO_DESCRIPTOR, containingFunction);
        if (containingFunctionDescriptor == null) {
            return false;
        }

        BindingContext bindingContext = trace.getBindingContext();

        while (canBeInlineArgument(containingFunction) && fromFunction != containingFunctionDescriptor) {
            if (!isInlinedArgument((JetFunction) containingFunction, bindingContext, true)) {
                return false;
            }

            containingFunctionDescriptor = getContainingClassOrFunctionDescriptor(containingFunctionDescriptor, true);

            containingFunction = containingFunctionDescriptor != null
                                 ? DescriptorToSourceUtils.descriptorToDeclaration(containingFunctionDescriptor)
                                 : null;
        }

        return fromFunction == containingFunctionDescriptor;
    }

    public static boolean isInlinedArgument(
            @NotNull JetFunction argument,
            @NotNull BindingContext bindingContext,
            boolean checkNonLocalReturn
    ) {
        if (!canBeInlineArgument(argument)) return false;

        JetExpression call = JetPsiUtil.getParentCallIfPresent(argument);
        if (call != null) {
            ResolvedCall<?> resolvedCall = CallUtilPackage.getResolvedCall(call, bindingContext);
            if (resolvedCall != null && isInline(resolvedCall.getResultingDescriptor())) {
                ValueArgument valueArgument = CallUtilPackage.getValueArgumentForExpression(resolvedCall.getCall(), argument);
                if (valueArgument != null) {
                    ArgumentMapping mapping = resolvedCall.getArgumentMapping(valueArgument);
                    if (mapping instanceof ArgumentMatch) {
                        ValueParameterDescriptor parameter = ((ArgumentMatch) mapping).getValueParameter();
                        if (isInlineLambdaParameter(parameter)) {
                            return !checkNonLocalReturn || allowsNonLocalReturns(parameter);
                        }
                    }
                }
            }
        }
        return false;
    }

    public static boolean canBeInlineArgument(@Nullable PsiElement functionalExpression) {
        return functionalExpression instanceof JetFunctionLiteral || functionalExpression instanceof JetNamedFunction;
    }

    @Nullable
    public static DeclarationDescriptor getContainingClassOrFunctionDescriptor(@NotNull DeclarationDescriptor descriptor, boolean strict) {
        DeclarationDescriptor current = strict ? descriptor.getContainingDeclaration() : descriptor;
        while (current != null) {
            if (current instanceof FunctionDescriptor || current instanceof ClassDescriptor) {
                return current;
            }
            current = current.getContainingDeclaration();
        }

        return null;
    }

    public static boolean allowsNonLocalReturns(@NotNull CallableDescriptor lambda) {
        if (lambda instanceof ValueParameterDescriptor) {
            if (hasOnlyLocalReturn((ValueParameterDescriptor) lambda)) {
                //annotated
                return false;
            }
        }
        return true;
    }
}
