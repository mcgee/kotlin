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

package org.jetbrains.kotlin.idea.refactoring.pushDown

import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.refactoring.pullUp.renderForConflicts
import org.jetbrains.kotlin.idea.references.JetReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.JetTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForReceiver
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.getCalleeExpressionIfAny
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.QualifierReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.substitutions.getTypeSubstitutor
import org.jetbrains.kotlin.util.findCallableMemberBySignature
import java.util.ArrayList

fun analyzePushDownConflicts(context: KotlinPushDownContext,
                             usages: Array<out UsageInfo>): MultiMap<PsiElement, String> {
    val targetClasses = usages.map { it.element?.unwrapped }.filterNotNull()

    val conflicts = MultiMap<PsiElement, String>()

    val membersToPush = ArrayList<JetNamedDeclaration>()
    val membersToKeepAbstract = ArrayList<JetNamedDeclaration>()
    for (info in context.membersToMove) {
        val member = info.member
        if (!info.isChecked || (member is JetClassOrObject && info.overrides != null)) continue

        membersToPush += member
        if ((member is JetNamedFunction || member is JetProperty)
            && info.isToAbstract
            && (context.memberDescriptors[member] as CallableMemberDescriptor).modality != Modality.ABSTRACT) {
            membersToKeepAbstract += member
        }
    }

    for (targetClass in targetClasses) {
        checkConflicts(conflicts, context, targetClass, membersToKeepAbstract, membersToPush)
    }

    return conflicts
}

private fun checkConflicts(
        conflicts: MultiMap<PsiElement, String>,
        context: KotlinPushDownContext,
        targetClass: PsiElement,
        membersToKeepAbstract: List<JetNamedDeclaration>,
        membersToPush: ArrayList<JetNamedDeclaration>
) {
    if (targetClass !is JetClassOrObject) {
        conflicts.putValue(
                targetClass,
                "Non-Kotlin ${RefactoringUIUtil.getDescription(targetClass, false)} won't be affected by the refactoring"
        )
        return
    }

    val targetClassDescriptor = context.resolutionFacade.resolveToDescriptor(targetClass) as ClassDescriptor
    val substitutor = getTypeSubstitutor(context.sourceClassDescriptor.defaultType, targetClassDescriptor.defaultType)
                      ?: TypeSubstitutor.EMPTY

    if (!context.sourceClass.isInterface() && targetClass is JetClass && targetClass.isInterface()) {
        val message = "${targetClassDescriptor.renderForConflicts()} " +
                      "inherits from ${context.sourceClassDescriptor.renderForConflicts()}.\n" +
                      "It won't be affected by the refactoring"
        conflicts.putValue(targetClass, message.capitalize())
    }

    for (member in membersToPush) {
        checkMemberClashing(conflicts, context, member, membersToKeepAbstract, substitutor, targetClass, targetClassDescriptor)
        checkSuperCalls(conflicts, context, member, membersToPush)
        checkExternalUsages(conflicts, context, member, targetClassDescriptor)
        checkVisibility(conflicts, context, member, targetClassDescriptor)
    }
}

private fun checkMemberClashing(
        conflicts: MultiMap<PsiElement, String>,
        context: KotlinPushDownContext,
        member: JetNamedDeclaration,
        membersToKeepAbstract: List<JetNamedDeclaration>,
        substitutor: TypeSubstitutor,
        targetClass: JetClassOrObject,
        targetClassDescriptor: ClassDescriptor) {
    when (member) {
        is JetNamedFunction, is JetProperty -> {
            val memberDescriptor = context.memberDescriptors[member] as CallableMemberDescriptor
            val clashingDescriptor = targetClassDescriptor.findCallableMemberBySignature(memberDescriptor.substitute(substitutor) as CallableMemberDescriptor)
            val clashingDeclaration = clashingDescriptor?.source?.getPsi() as? JetNamedDeclaration
            if (clashingDescriptor != null && clashingDeclaration != null) {
                if (memberDescriptor.modality != Modality.ABSTRACT && member !in membersToKeepAbstract) {
                    val message = "${targetClassDescriptor.renderForConflicts()} already contains ${clashingDescriptor.renderForConflicts()}"
                    conflicts.putValue(clashingDeclaration, CommonRefactoringUtil.capitalize(message))
                }
                if (!clashingDeclaration.hasModifier(JetTokens.OVERRIDE_KEYWORD)) {
                    val message = "${clashingDescriptor.renderForConflicts()} in ${targetClassDescriptor.renderForConflicts()} " +
                                  "will override corresponding member of ${context.sourceClassDescriptor.renderForConflicts()} " +
                                  "after refactoring"
                    conflicts.putValue(clashingDeclaration, CommonRefactoringUtil.capitalize(message))
                }
            }
        }

        is JetClassOrObject -> {
            targetClass.declarations
                    .filterIsInstance<JetClassOrObject>()
                    .firstOrNull() { it.name == member.name }
                    ?.let {
                        val message = "${targetClassDescriptor.renderForConflicts()} " +
                                      "already contains nested class named ${CommonRefactoringUtil.htmlEmphasize(member.name)}"
                        conflicts.putValue(it, message.capitalize())
                    }
        }
    }
}

private fun checkSuperCalls(
        conflicts: MultiMap<PsiElement, String>,
        context: KotlinPushDownContext,
        member: JetNamedDeclaration,
        membersToPush: ArrayList<JetNamedDeclaration>
) {
    member.accept(
            object : JetTreeVisitorVoid() {
                override fun visitSuperExpression(expression: JetSuperExpression) {
                    val qualifiedExpression = expression.getQualifiedExpressionForReceiver() ?: return
                    val refExpr = qualifiedExpression.selectorExpression.getCalleeExpressionIfAny() as? JetSimpleNameExpression ?: return
                    for (descriptor in refExpr.mainReference.resolveToDescriptors(context.sourceClassContext)) {
                        val memberDescriptor = descriptor as? CallableMemberDescriptor ?: continue
                        val containingClass = memberDescriptor.containingDeclaration as? ClassDescriptor ?: continue
                        if (!DescriptorUtils.isSubclass(context.sourceClassDescriptor, containingClass)) continue
                        val memberInSource = context.sourceClassDescriptor.findCallableMemberBySignature(memberDescriptor)?.source?.getPsi()
                                             ?: continue
                        if (memberInSource !in membersToPush) {
                            conflicts.putValue(qualifiedExpression,
                                               "Pushed member won't be available in '${qualifiedExpression.text}'")
                        }
                    }
                }
            }
    )
}

private fun checkExternalUsages(
        conflicts: MultiMap<PsiElement, String>,
        context: KotlinPushDownContext,
        member: JetNamedDeclaration,
        targetClassDescriptor: ClassDescriptor
) {
    for (ref in ReferencesSearch.search(member, member.resolveScope, false)) {
        val calleeExpr = ref.element as? JetSimpleNameExpression ?: continue
        val resolvedCall = calleeExpr.getResolvedCall(context.resolutionFacade.analyze(calleeExpr)) ?: continue
        val callElement = resolvedCall.call.callElement
        val dispatchReceiver = resolvedCall.dispatchReceiver
        if (!dispatchReceiver.exists() || dispatchReceiver is QualifierReceiver) continue
        val receiverClassDescriptor = dispatchReceiver.type.constructor.declarationDescriptor as? ClassDescriptor ?: continue
        if (!DescriptorUtils.isSubclass(receiverClassDescriptor, targetClassDescriptor)) {
            conflicts.putValue(callElement, "Pushed member won't be available in '${callElement.text}'")
        }
    }
}

private fun checkVisibility(
        conflicts: MultiMap<PsiElement, String>,
        context: KotlinPushDownContext,
        member: JetNamedDeclaration,
        targetClassDescriptor: ClassDescriptor
) {
    fun reportConflictIfAny(targetDescriptor: DeclarationDescriptor) {
        val target = (targetDescriptor as? DeclarationDescriptorWithSource)?.source?.getPsi() ?: return
        if (targetDescriptor is DeclarationDescriptorWithVisibility
            && !Visibilities.isVisible(ReceiverValue.IRRELEVANT_RECEIVER, targetDescriptor, targetClassDescriptor)) {
            val message = "${context.memberDescriptors[member]!!.renderForConflicts()} " +
                          "uses ${targetDescriptor.renderForConflicts()}, " +
                          "which is not accessible from the ${targetClassDescriptor.renderForConflicts()}"
            conflicts.putValue(target, message.capitalize())
        }
    }

    member.accept(
            object : JetTreeVisitorVoid() {
                override fun visitReferenceExpression(expression: JetReferenceExpression) {
                    super.visitReferenceExpression(expression)

                    expression.references
                            .flatMap { (it as? JetReference)?.resolveToDescriptors(context.sourceClassContext) ?: emptyList() }
                            .forEach(::reportConflictIfAny)

                }
            }
    )
}
