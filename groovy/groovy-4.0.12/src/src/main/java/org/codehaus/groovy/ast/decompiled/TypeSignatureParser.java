/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.ast.decompiled;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.vmplugin.v8.Java8;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.List;

import static org.codehaus.groovy.control.CompilerConfiguration.ASM_API_VERSION;

abstract class TypeSignatureParser extends SignatureVisitor {

    private final AsmReferenceResolver resolver;

    public TypeSignatureParser(final AsmReferenceResolver resolver) {
        super(ASM_API_VERSION);
        this.resolver = resolver;
    }

    protected static ClassNode applyErasure(final ClassNode genericType, final ClassNode erasure) {
        if (genericType.isArray() && erasure.isArray() && genericType.getComponentType().isGenericsPlaceHolder()) {
            genericType.setRedirect(erasure);
            genericType.getComponentType().setRedirect(erasure.getComponentType());
        } else if (genericType.isGenericsPlaceHolder()) {
            genericType.setRedirect(erasure);
        }
        return genericType;
    }

    abstract void finished(ClassNode result);

    private String baseName;
    private final List<GenericsType> arguments = new ArrayList<>();

    @Override
    public void visitTypeVariable(final String name) {
        finished(Java8.configureTypeVariableReference(name));
    }

    @Override
    public void visitBaseType(final char descriptor) {
        finished(resolver.resolveType(Type.getType(String.valueOf(descriptor))));
    }

    @Override
    public SignatureVisitor visitArrayType() {
        final TypeSignatureParser outer = this;
        return new TypeSignatureParser(resolver) {
            @Override
            void finished(final ClassNode result) {
                outer.finished(result.makeArray());
            }
        };
    }

    @Override
    public void visitClassType(final String name) {
        baseName = AsmDecompiler.fromInternalName(name);
    }

    @Override
    public void visitTypeArgument() {
        arguments.add(createWildcard(null, null));
    }

    @Override
    public SignatureVisitor visitTypeArgument(final char wildcard) {
        return new TypeSignatureParser(resolver) {
            @Override
            void finished(ClassNode result) {
                if (wildcard == INSTANCEOF) {
                    arguments.add(new GenericsType(result));
                    return;
                }

                ClassNode[] upper = wildcard == EXTENDS ? new ClassNode[]{result} : null;
                ClassNode lower = wildcard == SUPER ? result : null;
                arguments.add(createWildcard(upper, lower));
            }
        };
    }

    @Override
    public void visitInnerClassType(final String name) {
        baseName += "$" + name;
        arguments.clear();
    }

    @Override
    public void visitEnd() {
        ClassNode baseType = resolver.resolveClass(baseName);
        if (arguments.isEmpty() && isNotParameterized(baseType)) {
            finished(baseType);
        } else {
            ClassNode parameterizedType = baseType.getPlainNodeReference();
            if (arguments.isEmpty()) {
                // GROOVY-10234: no type arguments -> raw type
            } else {
                try {
                    // GROOVY-10153, GROOVY-10651, GROOVY-10671: "?" or "? super T" (see ResolveVisitor#resolveWildcardBounding)
                    for (int i = 0, n = arguments.size(); i < n; i += 1) { GenericsType argument = arguments.get(i);
                    if (!argument.isWildcard() || argument.getUpperBounds() != null) continue; //
                    ClassNode[] implicitBounds = baseType.getGenericsTypes()[i].getUpperBounds();
                    if (implicitBounds != null && !ClassHelper.isObjectType(implicitBounds[0])) {
                        argument.getType().setRedirect(implicitBounds[0]); // bound is not Object
                    }
                    }
                } catch (StackOverflowError ignore) {
                    // TODO: self-referential type parameter
                }
                parameterizedType.setGenericsTypes(arguments.toArray(GenericsType.EMPTY_ARRAY));
            }
            finished(parameterizedType);
        }
    }

    //--------------------------------------------------------------------------

    private static GenericsType createWildcard(final ClassNode[] upper, final ClassNode lower) {
        ClassNode base = ClassHelper.makeWithoutCaching("?");
        base.setRedirect(ClassHelper.OBJECT_TYPE);
        GenericsType t = new GenericsType(base, upper, lower);
        t.setWildcard(true);
        return t;
    }

    private static boolean isNotParameterized(final ClassNode cn) {
        // DecompiledClassNode may not have generics initialized
        if (cn instanceof DecompiledClassNode) {
            return !((DecompiledClassNode) cn).isParameterized();
        }
        return (cn.getGenericsTypes() == null);
    }
}
