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

package org.jetbrains.kotlin.resolve.jvm.kotlinSignature;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiNamedElement;
import com.intellij.util.containers.ComparatorUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.descriptors.SourceElement;
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor;
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor;
import org.jetbrains.kotlin.descriptors.impl.TypeParameterDescriptorImpl;
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl;
import org.jetbrains.kotlin.load.java.structure.JavaMember;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt;
import org.jetbrains.kotlin.resolve.jvm.JavaResolverUtils;
import org.jetbrains.kotlin.resolve.jvm.JvmPackage;
import org.jetbrains.kotlin.types.JetType;
import org.jetbrains.kotlin.types.TypeSubstitutor;
import org.jetbrains.kotlin.types.TypeUtils;
import org.jetbrains.kotlin.types.Variance;
import org.jetbrains.kotlin.types.checker.JetTypeChecker;
import org.jetbrains.kotlin.types.typeUtil.TypeUtilPackage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jetbrains.kotlin.load.java.components.TypeUsage.MEMBER_SIGNATURE_CONTRAVARIANT;
import static org.jetbrains.kotlin.load.java.components.TypeUsage.UPPER_BOUND;
import static org.jetbrains.kotlin.psi.PsiPackage.JetPsiFactory;
import static org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt.getBuiltIns;

public class AlternativeMethodSignatureData extends ElementAlternativeSignatureData {
    private final JetNamedFunction altFunDeclaration;

    private List<ValueParameterDescriptor> altValueParameters;
    private JetType altReturnType;
    private List<TypeParameterDescriptor> altTypeParameters;

    private Map<TypeParameterDescriptor, TypeParameterDescriptorImpl> originalToAltTypeParameters;

    public AlternativeMethodSignatureData(
            @NotNull JavaMember methodOrConstructor,
            @Nullable JetType receiverType,
            @NotNull Project project,
            @NotNull List<ValueParameterDescriptor> valueParameters,
            @Nullable JetType originalReturnType,
            @NotNull List<TypeParameterDescriptor> methodTypeParameters,
            boolean hasSuperMethods
    ) {
        String signature = SignaturesUtil.getKotlinSignature(methodOrConstructor);

        if (signature == null) {
            setAnnotated(false);
            altFunDeclaration = null;
            return;
        }

        if (receiverType != null) {
            throw new UnsupportedOperationException("Alternative annotations for extension functions are not supported yet");
        }

        setAnnotated(true);
        altFunDeclaration = JetPsiFactory(project).createFunction(signature);

        originalToAltTypeParameters = JavaResolverUtils.recreateTypeParametersAndReturnMapping(methodTypeParameters, null);

        try {
            checkForSyntaxErrors(altFunDeclaration);
            checkEqualFunctionNames(altFunDeclaration, methodOrConstructor);

            computeTypeParameters(methodTypeParameters);
            computeValueParameters(valueParameters);

            if (originalReturnType != null) {
                altReturnType = computeReturnType(originalReturnType, altFunDeclaration.getTypeReference(), originalToAltTypeParameters);
            }

            if (hasSuperMethods) {
                checkParameterAndReturnTypesForOverridingMethods(valueParameters, methodTypeParameters, originalReturnType);
            }
        }
        catch (AlternativeSignatureMismatchException e) {
            setError(e.getMessage());
        }
    }

    public static List<ValueParameterDescriptor> updateNames(
            List<ValueParameterDescriptor> originalValueParameters,
            List<ValueParameterDescriptor> altValueParameters
    ) {
        List<ValueParameterDescriptor> result = new ArrayList<ValueParameterDescriptor>(originalValueParameters.size());
        for (int i = 0; i < originalValueParameters.size(); i++) {
            ValueParameterDescriptor originalValueParameter = originalValueParameters.get(i);
            ValueParameterDescriptor altValueParameter = altValueParameters.get(i);
            result.add(originalValueParameter.copy(originalValueParameter.getContainingDeclaration(), altValueParameter.getName()));
        }
        return result;
    }

    private void checkParameterAndReturnTypesForOverridingMethods(
            @NotNull List<ValueParameterDescriptor> valueParameters,
            @NotNull List<TypeParameterDescriptor> methodTypeParameters,
            @Nullable JetType returnType
    ) {
        if (JvmPackage.getPLATFORM_TYPES()) return;
        TypeSubstitutor substitutor = JavaResolverUtils.createSubstitutorForTypeParameters(originalToAltTypeParameters);

        for (ValueParameterDescriptor parameter : valueParameters) {
            int index = parameter.getIndex();
            ValueParameterDescriptor altParameter = altValueParameters.get(index);

            JetType substituted = substitutor.substitute(parameter.getType(), Variance.INVARIANT);
            assert substituted != null;

            if (!TypeUtils.equalTypes(substituted, altParameter.getType())) {
                throw new AlternativeSignatureMismatchException(
                        "Parameter type changed for method which overrides another: " + altParameter.getType()
                        + ", was: " + parameter.getType());
            }
        }

        // don't check receiver

        for (TypeParameterDescriptor parameter : methodTypeParameters) {
            int index = parameter.getIndex();

            JetType substituted = substitutor.substitute(parameter.getUpperBoundsAsType(), Variance.INVARIANT);
            assert substituted != null;

            if (!TypeUtils.equalTypes(substituted, altTypeParameters.get(index).getUpperBoundsAsType())) {
                throw new AlternativeSignatureMismatchException(
                        "Type parameter's upper bound changed for method which overrides another: "
                        + altTypeParameters.get(index).getUpperBoundsAsType() + ", was: " + parameter.getUpperBoundsAsType());
            }
        }

        if (returnType != null) {
            JetType substitutedReturnType = substitutor.substitute(returnType, Variance.INVARIANT);
            assert substitutedReturnType != null;

            if (!JetTypeChecker.DEFAULT.isSubtypeOf(altReturnType, substitutedReturnType)) {
                throw new AlternativeSignatureMismatchException(
                        "Return type is changed to not subtype for method which overrides another: " + altReturnType + ", was: " + returnType);
            }
        }
    }

    @NotNull
    public List<ValueParameterDescriptor> getValueParameters() {
        checkForErrors();
        return altValueParameters;
    }

    @Nullable
    public JetType getReturnType() {
        checkForErrors();
        return altReturnType;
    }

    @NotNull
    public List<TypeParameterDescriptor> getTypeParameters() {
        checkForErrors();
        return altTypeParameters;
    }

    private void computeValueParameters(@NotNull List<ValueParameterDescriptor> parameterDescriptors) {
        if (parameterDescriptors.size() != altFunDeclaration.getValueParameters().size()) {
            throw new AlternativeSignatureMismatchException("Method signature has %d value parameters, but alternative signature has %d",
                                                            parameterDescriptors.size(), altFunDeclaration.getValueParameters().size());
        }

        List<ValueParameterDescriptor> altParamDescriptors = new ArrayList<ValueParameterDescriptor>(parameterDescriptors.size());
        for (int i = 0; i < parameterDescriptors.size(); i++) {
            ValueParameterDescriptor originalParameterDescriptor = parameterDescriptors.get(i);
            JetParameter annotationValueParameter = altFunDeclaration.getValueParameters().get(i);

            //noinspection ConstantConditions
            JetTypeElement alternativeTypeElement = annotationValueParameter.getTypeReference().getTypeElement();
            assert alternativeTypeElement != null;

            JetType alternativeType;
            JetType alternativeVarargElementType;

            JetType originalParamVarargElementType = originalParameterDescriptor.getVarargElementType();
            if (originalParamVarargElementType == null) {
                if (annotationValueParameter.isVarArg()) {
                    throw new AlternativeSignatureMismatchException("Parameter in method signature is not vararg, but in alternative signature it is vararg");
                }

                alternativeType = TypeTransformingVisitor.computeType(alternativeTypeElement, originalParameterDescriptor.getType(), originalToAltTypeParameters, MEMBER_SIGNATURE_CONTRAVARIANT);
                alternativeVarargElementType = null;
            }
            else {
                if (!annotationValueParameter.isVarArg()) {
                    throw new AlternativeSignatureMismatchException("Parameter in method signature is vararg, but in alternative signature it is not");
                }

                alternativeVarargElementType = TypeTransformingVisitor.computeType(alternativeTypeElement, originalParamVarargElementType,
                                                                                   originalToAltTypeParameters, MEMBER_SIGNATURE_CONTRAVARIANT);
                alternativeType = getBuiltIns(originalParameterDescriptor).getArrayType(Variance.OUT_VARIANCE, alternativeVarargElementType);
            }

            Name altName = annotationValueParameter.getNameAsName();

            altParamDescriptors.add(new ValueParameterDescriptorImpl(
                    originalParameterDescriptor.getContainingDeclaration(),
                    null,
                    originalParameterDescriptor.getIndex(),
                    originalParameterDescriptor.getAnnotations(),
                    altName != null ? altName : originalParameterDescriptor.getName(),
                    alternativeType,
                    originalParameterDescriptor.declaresDefaultValue(),
                    alternativeVarargElementType,
                    SourceElement.NO_SOURCE
            ));
        }

        altValueParameters = altParamDescriptors;
    }

    private void computeTypeParameters(List<TypeParameterDescriptor> typeParameters) {
        if (typeParameters.size() != altFunDeclaration.getTypeParameters().size()) {
            throw new AlternativeSignatureMismatchException("Method signature has %d type parameters, but alternative signature has %d",
                                                            typeParameters.size(), altFunDeclaration.getTypeParameters().size());
        }

        altTypeParameters = new ArrayList<TypeParameterDescriptor>(typeParameters.size());

        for (int i = 0; i < typeParameters.size(); i++) {
            TypeParameterDescriptor originalTypeParamDescriptor = typeParameters.get(i);

            TypeParameterDescriptorImpl altParamDescriptor = originalToAltTypeParameters.get(originalTypeParamDescriptor);
            JetTypeParameter altTypeParameter = altFunDeclaration.getTypeParameters().get(i);

            Set<JetType> originalUpperBounds = originalTypeParamDescriptor.getUpperBounds();
            List<JetTypeReference> altUpperBounds = getUpperBounds(altFunDeclaration, altTypeParameter);
            if (altUpperBounds.size() != originalUpperBounds.size()) {
                if (altUpperBounds.isEmpty()
                    && originalUpperBounds.size() == 1
                    && TypeUtilPackage.isDefaultBound(originalUpperBounds.iterator().next())) {
                    // Only default bound => no error
                }
                else {
                    throw new AlternativeSignatureMismatchException("Upper bound number mismatch for %s. Expected %d, but found %d",
                                                                    originalTypeParamDescriptor.getName(),
                                                                    originalUpperBounds.size(),
                                                                    altUpperBounds.size());
                }
            }

            if (altUpperBounds.isEmpty()) {
                altParamDescriptor.addDefaultUpperBound();
            }
            else {
                int upperBoundIndex = 0;
                for (JetType upperBound : originalUpperBounds) {

                    JetTypeElement altTypeElement = altUpperBounds.get(upperBoundIndex).getTypeElement();
                    assert altTypeElement != null;

                    altParamDescriptor.addUpperBound(TypeTransformingVisitor.computeType(altTypeElement, upperBound,
                                                                                         originalToAltTypeParameters, UPPER_BOUND));
                    upperBoundIndex++;
                }
            }

            altParamDescriptor.setInitialized();
            altTypeParameters.add(altParamDescriptor);
        }
    }

    @NotNull
    private static List<JetTypeReference> getUpperBounds(@NotNull JetFunction function, @NotNull JetTypeParameter jetTypeParameter) {
        List<JetTypeReference> result = new ArrayList<JetTypeReference>();
        ContainerUtil.addIfNotNull(result, jetTypeParameter.getExtendsBound());

        Name name = jetTypeParameter.getNameAsName();
        if (name == null) return result;

        for (JetTypeConstraint constraint : function.getTypeConstraints()) {
            JetSimpleNameExpression parameterName = constraint.getSubjectTypeParameterName();
            assert parameterName != null : "No parameter name in constraint " + constraint.getText();
            if (name.equals(parameterName.getReferencedNameAsName())) {
                result.add(constraint.getBoundTypeReference());
            }
        }

        return result;
    }

    private static void checkEqualFunctionNames(@NotNull PsiNamedElement namedElement, @NotNull JavaMember methodOrConstructor) {
        if (!ComparatorUtil.equalsNullable(methodOrConstructor.getName().asString(), namedElement.getName())) {
            throw new AlternativeSignatureMismatchException("Function names mismatch, original: %s, alternative: %s",
                                                            methodOrConstructor.getName().asString(), namedElement.getName());
        }
    }
}
