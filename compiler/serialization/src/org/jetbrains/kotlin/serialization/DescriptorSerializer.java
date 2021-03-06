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

package org.jetbrains.kotlin.serialization;

import com.google.protobuf.MessageLite;
import kotlin.CollectionsKt;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.annotations.Annotated;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.resolve.MemberComparator;
import org.jetbrains.kotlin.resolve.constants.ConstantValue;
import org.jetbrains.kotlin.resolve.constants.NullValue;
import org.jetbrains.kotlin.types.*;
import org.jetbrains.kotlin.utils.Interner;
import org.jetbrains.kotlin.utils.UtilsPackage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static org.jetbrains.kotlin.resolve.DescriptorUtils.isEnumEntry;

public class DescriptorSerializer {

    private final Interner<TypeParameterDescriptor> typeParameters;
    private final SerializerExtension extension;

    private DescriptorSerializer(Interner<TypeParameterDescriptor> typeParameters, SerializerExtension extension) {
        this.typeParameters = typeParameters;
        this.extension = extension;
    }

    @NotNull
    public byte[] serialize(@NotNull MessageLite message) {
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            getStringTable().serializeTo(result);
            message.writeTo(result);
            return result.toByteArray();
        }
        catch (IOException e) {
            throw UtilsPackage.rethrow(e);
        }
    }

    @NotNull
    public static DescriptorSerializer createTopLevel(@NotNull SerializerExtension extension) {
        return new DescriptorSerializer(new Interner<TypeParameterDescriptor>(), extension);
    }

    @NotNull
    public static DescriptorSerializer create(@NotNull ClassDescriptor descriptor, @NotNull SerializerExtension extension) {
        DeclarationDescriptor container = descriptor.getContainingDeclaration();
        DescriptorSerializer parentSerializer =
                container instanceof ClassDescriptor
                ? create((ClassDescriptor) container, extension)
                : createTopLevel(extension);

        // Calculate type parameter ids for the outer class beforehand, as it would've had happened if we were always
        // serializing outer classes before nested classes.
        // Otherwise our interner can get wrong ids because we may serialize classes in any order.
        DescriptorSerializer serializer = parentSerializer.createChildSerializer();
        for (TypeParameterDescriptor typeParameter : descriptor.getTypeConstructor().getParameters()) {
            serializer.typeParameters.intern(typeParameter);
        }
        return serializer;
    }

    @NotNull
    private DescriptorSerializer createChildSerializer() {
        return new DescriptorSerializer(new Interner<TypeParameterDescriptor>(typeParameters), extension);
    }

    @NotNull
    public StringTable getStringTable() {
        return extension.getStringTable();
    }

    @NotNull
    public ProtoBuf.Class.Builder classProto(@NotNull ClassDescriptor classDescriptor) {
        ProtoBuf.Class.Builder builder = ProtoBuf.Class.newBuilder();

        int flags = Flags.getClassFlags(hasAnnotations(classDescriptor), classDescriptor.getVisibility(), classDescriptor.getModality(),
                                        classDescriptor.getKind(), classDescriptor.isInner(), classDescriptor.isCompanionObject());
        if (flags != builder.getFlags()) {
            builder.setFlags(flags);
        }

        builder.setFqName(getClassId(classDescriptor));

        for (TypeParameterDescriptor typeParameterDescriptor : classDescriptor.getTypeConstructor().getParameters()) {
            builder.addTypeParameter(typeParameter(typeParameterDescriptor));
        }

        if (!KotlinBuiltIns.isSpecialClassWithNoSupertypes(classDescriptor)) {
            // Special classes (Any, Nothing) have no supertypes
            for (JetType supertype : classDescriptor.getTypeConstructor().getSupertypes()) {
                builder.addSupertype(type(supertype));
            }
        }

        for (ConstructorDescriptor descriptor : classDescriptor.getConstructors()) {
            builder.addConstructor(constructorProto(descriptor));
        }

        for (DeclarationDescriptor descriptor : sort(classDescriptor.getDefaultType().getMemberScope().getAllDescriptors())) {
            if (descriptor instanceof CallableMemberDescriptor) {
                CallableMemberDescriptor member = (CallableMemberDescriptor) descriptor;
                if (member.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) continue;

                if (descriptor instanceof PropertyDescriptor) {
                    builder.addProperty(propertyProto((PropertyDescriptor) descriptor));
                }
                else if (descriptor instanceof FunctionDescriptor) {
                    builder.addFunction(functionProto((FunctionDescriptor) descriptor));
                }
            }
        }

        for (DeclarationDescriptor descriptor : sort(classDescriptor.getUnsubstitutedInnerClassesScope().getAllDescriptors())) {
            int name = getSimpleNameIndex(descriptor.getName());
            if (isEnumEntry(descriptor)) {
                builder.addEnumEntry(name);
            }
            else {
                builder.addNestedClassName(name);
            }
        }

        ClassDescriptor companionObjectDescriptor = classDescriptor.getCompanionObjectDescriptor();
        if (companionObjectDescriptor != null) {
            builder.setCompanionObjectName(getSimpleNameIndex(companionObjectDescriptor.getName()));
        }

        extension.serializeClass(classDescriptor, builder);

        return builder;
    }

    @NotNull
    public ProtoBuf.Property.Builder propertyProto(@NotNull PropertyDescriptor descriptor) {
        ProtoBuf.Property.Builder builder = ProtoBuf.Property.newBuilder();

        DescriptorSerializer local = createChildSerializer();

        boolean hasGetter = false;
        boolean hasSetter = false;
        boolean lateInit = descriptor.isLateInit();
        boolean isConst = descriptor.isConst();

        ConstantValue<?> compileTimeConstant = descriptor.getCompileTimeInitializer();
        boolean hasConstant = !(compileTimeConstant == null || compileTimeConstant instanceof NullValue);

        boolean hasAnnotations = !descriptor.getAnnotations().getAllAnnotations().isEmpty();

        int propertyFlags = Flags.getAccessorFlags(
                hasAnnotations,
                descriptor.getVisibility(),
                descriptor.getModality(),
                false
        );

        PropertyGetterDescriptor getter = descriptor.getGetter();
        if (getter != null) {
            hasGetter = true;
            int accessorFlags = getAccessorFlags(getter);
            if (accessorFlags != propertyFlags) {
                builder.setGetterFlags(accessorFlags);
            }
        }

        PropertySetterDescriptor setter = descriptor.getSetter();
        if (setter != null) {
            hasSetter = true;
            int accessorFlags = getAccessorFlags(setter);
            if (accessorFlags != propertyFlags) {
                builder.setSetterFlags(accessorFlags);
            }

            if (!setter.isDefault()) {
                for (ValueParameterDescriptor valueParameterDescriptor : setter.getValueParameters()) {
                    builder.setSetterValueParameter(local.valueParameter(valueParameterDescriptor));
                }
            }
        }

        int flags = Flags.getPropertyFlags(
                hasAnnotations, descriptor.getVisibility(), descriptor.getModality(), descriptor.getKind(), descriptor.isVar(),
                hasGetter, hasSetter, hasConstant, isConst, lateInit
        );
        if (flags != builder.getFlags()) {
            builder.setFlags(flags);
        }

        builder.setName(getSimpleNameIndex(descriptor.getName()));

        builder.setReturnType(local.type(descriptor.getType()));

        for (TypeParameterDescriptor typeParameterDescriptor : descriptor.getTypeParameters()) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor));
        }

        ReceiverParameterDescriptor receiverParameter = descriptor.getExtensionReceiverParameter();
        if (receiverParameter != null) {
            builder.setReceiverType(local.type(receiverParameter.getType()));
        }

        extension.serializeProperty(descriptor, builder);

        return builder;
    }

    @NotNull
    public ProtoBuf.Function.Builder functionProto(@NotNull FunctionDescriptor descriptor) {
        ProtoBuf.Function.Builder builder = ProtoBuf.Function.newBuilder();

        DescriptorSerializer local = createChildSerializer();

        int flags = Flags.getFunctionFlags(
                hasAnnotations(descriptor), descriptor.getVisibility(), descriptor.getModality(), descriptor.getKind(),
                descriptor.isOperator(), descriptor.isInfix()
        );
        if (flags != builder.getFlags()) {
            builder.setFlags(flags);
        }

        builder.setName(getSimpleNameIndex(descriptor.getName()));

        //noinspection ConstantConditions
        builder.setReturnType(local.type(descriptor.getReturnType()));

        for (TypeParameterDescriptor typeParameterDescriptor : descriptor.getTypeParameters()) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor));
        }

        ReceiverParameterDescriptor receiverParameter = descriptor.getExtensionReceiverParameter();
        if (receiverParameter != null) {
            builder.setReceiverType(local.type(receiverParameter.getType()));
        }

        for (ValueParameterDescriptor valueParameterDescriptor : descriptor.getValueParameters()) {
            builder.addValueParameter(local.valueParameter(valueParameterDescriptor));
        }

        extension.serializeFunction(descriptor, builder);

        return builder;
    }

    @NotNull
    public ProtoBuf.Constructor.Builder constructorProto(@NotNull ConstructorDescriptor descriptor) {
        ProtoBuf.Constructor.Builder builder = ProtoBuf.Constructor.newBuilder();

        DescriptorSerializer local = createChildSerializer();

        int flags = Flags.getConstructorFlags(hasAnnotations(descriptor), descriptor.getVisibility(), !descriptor.isPrimary());
        if (flags != builder.getFlags()) {
            builder.setFlags(flags);
        }

        for (ValueParameterDescriptor valueParameterDescriptor : descriptor.getValueParameters()) {
            builder.addValueParameter(local.valueParameter(valueParameterDescriptor));
        }

        extension.serializeConstructor(descriptor, builder);

        return builder;
    }

    private static int getAccessorFlags(@NotNull PropertyAccessorDescriptor accessor) {
        return Flags.getAccessorFlags(
                hasAnnotations(accessor),
                accessor.getVisibility(),
                accessor.getModality(),
                !accessor.isDefault()
        );
    }

    @NotNull
    private ProtoBuf.ValueParameter.Builder valueParameter(@NotNull ValueParameterDescriptor descriptor) {
        ProtoBuf.ValueParameter.Builder builder = ProtoBuf.ValueParameter.newBuilder();

        int flags = Flags.getValueParameterFlags(hasAnnotations(descriptor), descriptor.declaresDefaultValue());
        if (flags != builder.getFlags()) {
            builder.setFlags(flags);
        }

        builder.setName(getSimpleNameIndex(descriptor.getName()));

        builder.setType(type(descriptor.getType()));

        JetType varargElementType = descriptor.getVarargElementType();
        if (varargElementType != null) {
            builder.setVarargElementType(type(varargElementType));
        }

        extension.serializeValueParameter(descriptor, builder);

        return builder;
    }

    private ProtoBuf.TypeParameter.Builder typeParameter(TypeParameterDescriptor typeParameter) {
        ProtoBuf.TypeParameter.Builder builder = ProtoBuf.TypeParameter.newBuilder();

        builder.setId(getTypeParameterId(typeParameter));

        builder.setName(getSimpleNameIndex(typeParameter.getName()));

        if (typeParameter.isReified() != builder.getReified()) {
            builder.setReified(typeParameter.isReified());
        }

        ProtoBuf.TypeParameter.Variance variance = variance(typeParameter.getVariance());
        if (variance != builder.getVariance()) {
            builder.setVariance(variance);
        }

        Set<JetType> upperBounds = typeParameter.getUpperBounds();
        if (upperBounds.size() == 1 && KotlinBuiltIns.isDefaultBound(CollectionsKt.single(upperBounds))) return builder;

        for (JetType upperBound : upperBounds) {
            builder.addUpperBound(type(upperBound));
        }

        return builder;
    }

    private static ProtoBuf.TypeParameter.Variance variance(Variance variance) {
        switch (variance) {
            case INVARIANT:
                return ProtoBuf.TypeParameter.Variance.INV;
            case IN_VARIANCE:
                return ProtoBuf.TypeParameter.Variance.IN;
            case OUT_VARIANCE:
                return ProtoBuf.TypeParameter.Variance.OUT;
        }
        throw new IllegalStateException("Unknown variance: " + variance);
    }

    @NotNull
    public ProtoBuf.Type.Builder type(@NotNull JetType type) {
        assert !type.isError() : "Can't serialize error types: " + type; // TODO

        if (TypesPackage.isFlexible(type)) {
            Flexibility flexibility = TypesPackage.flexibility(type);

            return type(flexibility.getLowerBound())
                    .setFlexibleTypeCapabilitiesId(getStringTable().getStringIndex(flexibility.getExtraCapabilities().getId()))
                    .setFlexibleUpperBound(type(flexibility.getUpperBound()));
        }

        ProtoBuf.Type.Builder builder = ProtoBuf.Type.newBuilder();

        ClassifierDescriptor descriptor = type.getConstructor().getDeclarationDescriptor();
        if (descriptor instanceof ClassDescriptor) {
            builder.setClassName(getClassId((ClassDescriptor) descriptor));
        }
        if (descriptor instanceof TypeParameterDescriptor) {
            builder.setTypeParameter(getTypeParameterId((TypeParameterDescriptor) descriptor));
        }

        for (TypeProjection projection : type.getArguments()) {
            builder.addArgument(typeArgument(projection));
        }

        if (type.isMarkedNullable() != builder.getNullable()) {
            builder.setNullable(type.isMarkedNullable());
        }

        extension.serializeType(type, builder);

        return builder;
    }

    @NotNull
    private ProtoBuf.Type.Argument.Builder typeArgument(@NotNull TypeProjection typeProjection) {
        ProtoBuf.Type.Argument.Builder builder = ProtoBuf.Type.Argument.newBuilder();

        if (typeProjection.isStarProjection()) {
            builder.setProjection(ProtoBuf.Type.Argument.Projection.STAR);
        }
        else {
            ProtoBuf.Type.Argument.Projection projection = projection(typeProjection.getProjectionKind());

            if (projection != builder.getProjection()) {
                builder.setProjection(projection);
            }
            builder.setType(type(typeProjection.getType()));
        }

        return builder;
    }

    @NotNull
    public ProtoBuf.Package.Builder packageProto(@NotNull Collection<PackageFragmentDescriptor> fragments) {
        return packageProto(fragments, null);
    }

    @NotNull
    public ProtoBuf.Package.Builder packageProtoWithoutDescriptors() {
        ProtoBuf.Package.Builder builder = ProtoBuf.Package.newBuilder();

        extension.serializePackage(Collections.<PackageFragmentDescriptor>emptyList(), builder);

        return builder;
    }

    @NotNull
    public ProtoBuf.Package.Builder packageProto(@NotNull Collection<PackageFragmentDescriptor> fragments, @Nullable Function1<DeclarationDescriptor, Boolean> skip) {
        ProtoBuf.Package.Builder builder = ProtoBuf.Package.newBuilder();

        Collection<DeclarationDescriptor> members = new ArrayList<DeclarationDescriptor>();
        for (PackageFragmentDescriptor fragment : fragments) {
            members.addAll(fragment.getMemberScope().getAllDescriptors());
        }

        for (DeclarationDescriptor declaration : sort(members)) {
            if (skip != null && skip.invoke(declaration)) continue;

            if (declaration instanceof PropertyDescriptor) {
                builder.addProperty(propertyProto((PropertyDescriptor) declaration));
            }
            else if (declaration instanceof FunctionDescriptor) {
                builder.addFunction(functionProto((FunctionDescriptor) declaration));
            }
        }

        extension.serializePackage(fragments, builder);

        return builder;
    }

    @NotNull
    public ProtoBuf.Package.Builder packagePartProto(@NotNull Collection<DeclarationDescriptor> members) {
        ProtoBuf.Package.Builder builder = ProtoBuf.Package.newBuilder();

        for (DeclarationDescriptor declaration : sort(members)) {
            if (declaration instanceof PropertyDescriptor) {
                builder.addProperty(propertyProto((PropertyDescriptor) declaration));
            }
            else if (declaration instanceof FunctionDescriptor) {
                builder.addFunction(functionProto((FunctionDescriptor) declaration));
            }
        }

        return builder;
    }

    @NotNull
    private static ProtoBuf.Type.Argument.Projection projection(@NotNull Variance projectionKind) {
        switch (projectionKind) {
            case INVARIANT:
                return ProtoBuf.Type.Argument.Projection.INV;
            case IN_VARIANCE:
                return ProtoBuf.Type.Argument.Projection.IN;
            case OUT_VARIANCE:
                return ProtoBuf.Type.Argument.Projection.OUT;
        }
        throw new IllegalStateException("Unknown projectionKind: " + projectionKind);
    }

    private int getClassId(@NotNull ClassDescriptor descriptor) {
        return getStringTable().getFqNameIndex(descriptor);
    }

    private int getSimpleNameIndex(@NotNull Name name) {
        return getStringTable().getStringIndex(name.asString());
    }

    private int getTypeParameterId(@NotNull TypeParameterDescriptor descriptor) {
        return typeParameters.intern(descriptor);
    }

    private static boolean hasAnnotations(Annotated descriptor) {
        return !descriptor.getAnnotations().isEmpty();
    }

    @NotNull
    public static <T extends DeclarationDescriptor> List<T> sort(@NotNull Collection<T> descriptors) {
        List<T> result = new ArrayList<T>(descriptors);
        //NOTE: the exact comparator does matter here
        Collections.sort(result, MemberComparator.INSTANCE);
        return result;

    }
}
