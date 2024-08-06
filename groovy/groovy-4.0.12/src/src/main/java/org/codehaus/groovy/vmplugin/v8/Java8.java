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
package org.codehaus.groovy.vmplugin.v8;

import groovy.lang.GroovyObject;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.MetaClass;
import groovy.lang.MetaMethod;
import org.apache.groovy.lang.GroovyObjectHelper;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.GenericsType;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PackageNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.reflection.ReflectionUtils;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.codehaus.groovy.vmplugin.VMPlugin;
import org.codehaus.groovy.vmplugin.VMPluginFactory;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.MalformedParameterizedTypeException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.security.Permission;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Java 8 based functions.
 *
 * @since 2.5.0
 */
public class Java8 implements VMPlugin {

    private static final Method[] EMPTY_METHOD_ARRAY = new Method[0];
    private static final Annotation[] EMPTY_ANNOTATION_ARRAY = new Annotation[0];
    private static final Permission ACCESS_PERMISSION = new ReflectPermission("suppressAccessChecks");

    public static GenericsType configureTypeVariableDefinition(final ClassNode base, final ClassNode[] cBounds) {
        ClassNode redirect = base.redirect();
        base.setRedirect(null);
        GenericsType gt;
        if (cBounds == null || cBounds.length == 0) {
            gt = new GenericsType(base);
        } else {
            gt = new GenericsType(base, cBounds, null);
            gt.setName(base.getName());
            gt.setPlaceholder(true);
        }
        base.setRedirect(redirect);
        return gt;
    }

    private static ClassNode configureClass(final Class<?> c) {
        if (c.isPrimitive()) {
            return ClassHelper.make(c);
        } else {
            return ClassHelper.makeWithoutCaching(c, false);
        }
    }

    public static ClassNode configureTypeVariableReference(final String name) {
        ClassNode cn = ClassHelper.makeWithoutCaching(name);
        cn.setGenericsPlaceHolder(true);
        ClassNode cn2 = ClassHelper.makeWithoutCaching(name);
        cn2.setGenericsPlaceHolder(true);
        GenericsType[] gts = new GenericsType[]{new GenericsType(cn2)};
        cn.setGenericsTypes(gts);
        cn.setRedirect(ClassHelper.OBJECT_TYPE);
        return cn;
    }

    private static void setRetentionPolicy(final RetentionPolicy value, final AnnotationNode node) {
        switch (value) {
            case RUNTIME:
                node.setRuntimeRetention(true);
                break;
            case SOURCE:
                node.setSourceRetention(true);
                break;
            case CLASS:
                node.setClassRetention(true);
                break;
            default:
                throw new GroovyBugError("unsupported Retention " + value);
        }
    }

    private static void setMethodDefaultValue(final MethodNode mn, final Method m) {
        ConstantExpression cExp = new ConstantExpression(m.getDefaultValue());
        mn.setCode(new ReturnStatement(cExp));
        mn.setAnnotationDefault(true);
    }

    @Override
    public Class<?>[] getPluginDefaultGroovyMethods() {
        return new Class[]{PluginDefaultGroovyMethods.class};
    }

    @Override
    public Class<?>[] getPluginStaticGroovyMethods() {
        return MetaClassHelper.EMPTY_TYPE_ARRAY;
    }

    @Override
    public int getVersion() {
        return 8;
    }

    protected int getElementCode(final ElementType value) {
        switch (value) {
            case TYPE:
                return AnnotationNode.TYPE_TARGET;
            case CONSTRUCTOR:
                return AnnotationNode.CONSTRUCTOR_TARGET;
            case METHOD:
                return AnnotationNode.METHOD_TARGET;
            case FIELD:
                return AnnotationNode.FIELD_TARGET;
            case PARAMETER:
                return AnnotationNode.PARAMETER_TARGET;
            case LOCAL_VARIABLE:
                return AnnotationNode.LOCAL_VARIABLE_TARGET;
            case ANNOTATION_TYPE:
                return AnnotationNode.ANNOTATION_TARGET;
            case PACKAGE:
                return AnnotationNode.PACKAGE_TARGET;
            case TYPE_PARAMETER:
                return AnnotationNode.TYPE_PARAMETER_TARGET;
            case TYPE_USE:
                return AnnotationNode.TYPE_USE_TARGET;
            default:
                // falls through
        }
        String name = value.name();
        if ("MODULE".equals(name)) { // JDK 9+
            return AnnotationNode.TYPE_TARGET; // TODO Add MODULE_TARGET too?
        } else if ("RECORD_COMPONENT".equals(name)) { // JDK 16+
            return AnnotationNode.RECORD_COMPONENT_TARGET;
        } else {
            throw new GroovyBugError("unsupported Target " + value);
        }
    }

    @Override
    public void setAdditionalClassInformation(final ClassNode cn) {
        setGenericsTypes(cn);
    }

    private void setGenericsTypes(final ClassNode cn) {
        TypeVariable[] tvs = cn.getTypeClass().getTypeParameters();
        GenericsType[] gts = configureTypeVariable(tvs);
        cn.setGenericsTypes(gts);
    }

    private GenericsType[] configureTypeVariable(final TypeVariable[] tvs) {
        final int n = tvs.length;
        if (n == 0) return null;
        GenericsType[] gts = new GenericsType[n];
        for (int i = 0; i < n; i += 1) {
            gts[i] = configureTypeVariableDefinition(tvs[i]);
        }
        return gts;
    }

    private GenericsType configureTypeVariableDefinition(final TypeVariable tv) {
        return configureTypeVariableDefinition(configureTypeVariableReference(tv.getName()), configureTypes(tv.getBounds()));
    }

    private ClassNode[] configureTypes(final Type[] types) {
        final int n = types.length;
        if (n == 0) return null;
        ClassNode[] nodes = new ClassNode[n];
        for (int i = 0; i < n; i += 1) {
            nodes[i] = configureType(types[i]);
        }
        return nodes;
    }

    private ClassNode configureType(final Type type) {
        if (type instanceof WildcardType) {
            return configureWildcardType((WildcardType) type);
        } else if (type instanceof ParameterizedType) {
            return configureParameterizedType((ParameterizedType) type);
        } else if (type instanceof GenericArrayType) {
            return configureGenericArray((GenericArrayType) type);
        } else if (type instanceof TypeVariable) {
            return configureTypeVariableReference(((TypeVariable) type).getName());
        } else if (type instanceof Class) {
            return configureClass((Class<?>) type);
        } else if (type==null) {
            throw new GroovyBugError("Type is null. Most probably you let a transform reuse existing ClassNodes with generics information, that is now used in a wrong context.");
        } else {
            throw new GroovyBugError("unknown type: " + type + " := " + type.getClass());
        }
    }

    private ClassNode configureGenericArray(final GenericArrayType genericArrayType) {
        Type component = genericArrayType.getGenericComponentType();
        ClassNode node = configureType(component);
        return node.makeArray();
    }

    private ClassNode configureWildcardType(final WildcardType wildcardType) {
        ClassNode base = ClassHelper.makeWithoutCaching("?");
        base.setRedirect(ClassHelper.OBJECT_TYPE);

        ClassNode[] lowers = configureTypes(wildcardType.getLowerBounds());
        ClassNode[] uppers = configureTypes(wildcardType.getUpperBounds());
        // beware of [Object] upper bounds; often it's <?> or <? super T>
        if (lowers != null || wildcardType.getTypeName().equals("?")) {
            uppers = null;
        }

        GenericsType gt = new GenericsType(base, uppers, lowers != null ? lowers[0] : null);
        gt.setWildcard(true);

        ClassNode wt = ClassHelper.makeWithoutCaching(Object.class, false);
        wt.setGenericsTypes(new GenericsType[]{gt});
        return wt;
    }

    private ClassNode configureParameterizedType(final ParameterizedType parameterizedType) {
        ClassNode base = configureType(parameterizedType.getRawType());
        GenericsType[] gts = configureTypeArguments(parameterizedType.getActualTypeArguments());
        base.setGenericsTypes(gts);
        return base;
    }

    private GenericsType[] configureTypeArguments(final Type[] ta) {
        final int n = ta.length;
        if (n == 0) return null;
        GenericsType[] gts = new GenericsType[n];
        for (int i = 0; i < n; i += 1) {
            ClassNode t = configureType(ta[i]);
            if (ta[i] instanceof WildcardType) {
                GenericsType[] gen = t.getGenericsTypes();
                gts[i] = gen[0];
            } else {
                gts[i] = new GenericsType(t);
            }
        }
        return gts;
    }

    //

    @Override
    public void configureAnnotation(final AnnotationNode node) {
        ClassNode type = node.getClassNode();
        VMPlugin plugin = VMPluginFactory.getPlugin();
        List<AnnotationNode> annotations = type.getAnnotations();
        for (AnnotationNode an : annotations) {
            plugin.configureAnnotationNodeFromDefinition(an, node);
        }
        if (!node.getClassNode().getName().equals("java.lang.annotation.Retention")) {
            plugin.configureAnnotationNodeFromDefinition(node, node);
        }
    }

    protected void configureAnnotation(final AnnotationNode node, final Annotation annotation) {
        Class<?> type = annotation.annotationType();
        if (type == Retention.class) {
            Retention r = (Retention) annotation;
            RetentionPolicy value = r.value();
            setRetentionPolicy(value, node);
            node.setMember("value", new PropertyExpression(
                    new ClassExpression(ClassHelper.makeWithoutCaching(RetentionPolicy.class, false)),
                    value.toString()));
        } else if (type == Target.class) {
            Target t = (Target) annotation;
            ElementType[] elements = t.value();
            ListExpression elementExprs = new ListExpression();
            for (ElementType element : elements) {
                elementExprs.addExpression(new PropertyExpression(
                        new ClassExpression(ClassHelper.ELEMENT_TYPE_TYPE), element.name()));
            }
            node.setMember("value", elementExprs);
        } else {
            Method[] declaredMethods;
            try {
                declaredMethods = type.getDeclaredMethods();
            } catch (SecurityException se) {
                declaredMethods = EMPTY_METHOD_ARRAY;
            }
            for (Method declaredMethod : declaredMethods) {
                try {
                    Object value = declaredMethod.invoke(annotation);
                    Expression valueExpression = toAnnotationValueExpression(value);
                    if (valueExpression != null) node.setMember(declaredMethod.getName(), valueExpression);

                } catch (IllegalAccessException | InvocationTargetException ignore) {
                }
            }
        }
    }

    private void setAnnotationMetaData(final Annotation[] annotations, final AnnotatedNode target) {
        for (Annotation annotation : annotations) {
            target.addAnnotation(toAnnotationNode(annotation));
        }
    }

    private AnnotationNode toAnnotationNode(final Annotation annotation) {
        ClassNode type = ClassHelper.make(annotation.annotationType());
        AnnotationNode node = new AnnotationNode(type);
        configureAnnotation(node, annotation);
        return node;
    }

    private Expression toAnnotationValueExpression(final Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Character || value instanceof Boolean)
            return new ConstantExpression(value);

        if (value instanceof Class)
            return new ClassExpression(ClassHelper.makeWithoutCaching((Class<?>)value));

        if (value instanceof Annotation)
            return new AnnotationConstantExpression(toAnnotationNode((Annotation)value));

        if (value instanceof Enum)
            return new PropertyExpression(new ClassExpression(ClassHelper.makeWithoutCaching(value.getClass())), value.toString());

        if (value.getClass().isArray()) {
            ListExpression list = new ListExpression();
            for (int i = 0, n = Array.getLength(value); i < n; i += 1)
                list.addExpression(toAnnotationValueExpression(Array.get(value, i)));
            return list;
        }

        return null;
    }

    //

    @Override
    public void configureAnnotationNodeFromDefinition(final AnnotationNode definition, final AnnotationNode root) {
        ClassNode type = definition.getClassNode();
        final String typeName = type.getName();
        if ("java.lang.annotation.Retention".equals(typeName)) {
            Expression exp = definition.getMember("value");
            if (!(exp instanceof PropertyExpression)) return;
            PropertyExpression pe = (PropertyExpression) exp;
            String name = pe.getPropertyAsString();
            RetentionPolicy policy = RetentionPolicy.valueOf(name);
            setRetentionPolicy(policy, root);
        } else if ("java.lang.annotation.Target".equals(typeName)) {
            Expression exp = definition.getMember("value");
            if (!(exp instanceof ListExpression)) return;
            ListExpression le = (ListExpression) exp;
            int bitmap = 0;
            for (Expression e : le.getExpressions()) {
                if (!(e instanceof PropertyExpression)) return;
                PropertyExpression element = (PropertyExpression) e;
                String name = element.getPropertyAsString();
                ElementType value = ElementType.valueOf(name);
                bitmap |= getElementCode(value);
            }
            root.setAllowedTargets(bitmap);
        }
    }

    @Override
    public void configureClassNode(final CompileUnit compileUnit, final ClassNode classNode) {
        try {
            Class<?> clazz = classNode.getTypeClass();
            Field[] fields = clazz.getDeclaredFields();
            for (Field f : fields) {
                ClassNode ret = makeClassNode(compileUnit, f.getGenericType(), f.getType());
                FieldNode fn = new FieldNode(f.getName(), f.getModifiers(), ret, classNode, null);
                setAnnotationMetaData(f.getAnnotations(), fn);
                classNode.addField(fn);
            }
            Method[] methods = clazz.getDeclaredMethods();
            for (Method m : methods) {
                ClassNode ret = makeClassNode(compileUnit, m.getGenericReturnType(), m.getReturnType());
                Parameter[] params = makeParameters(compileUnit, m.getGenericParameterTypes(), m.getParameterTypes(), m.getParameterAnnotations(), m);
                ClassNode[] exceptions = makeClassNodes(compileUnit, m.getGenericExceptionTypes(), m.getExceptionTypes());
                MethodNode mn = new MethodNode(m.getName(), m.getModifiers(), ret, params, exceptions, null);
                mn.setSynthetic(m.isSynthetic());
                setMethodDefaultValue(mn, m);
                setAnnotationMetaData(m.getAnnotations(), mn);
                mn.setGenericsTypes(configureTypeVariable(m.getTypeParameters()));
                classNode.addMethod(mn);
            }
            Constructor[] constructors = clazz.getDeclaredConstructors();
            for (Constructor ctor : constructors) {
                Parameter[] params = makeParameters(compileUnit, ctor.getGenericParameterTypes(), ctor.getParameterTypes(), getConstructorParameterAnnotations(ctor), ctor);
                ClassNode[] exceptions = makeClassNodes(compileUnit, ctor.getGenericExceptionTypes(), ctor.getExceptionTypes());
                ConstructorNode cn = classNode.addConstructor(ctor.getModifiers(), params, exceptions, null);
                setAnnotationMetaData(ctor.getAnnotations(), cn);
            }

            Class<?> sc = clazz.getSuperclass();
            if (sc != null) classNode.setUnresolvedSuperClass(makeClassNode(compileUnit, clazz.getGenericSuperclass(), sc));
            makeInterfaceTypes(compileUnit, classNode, clazz);
            makePermittedSubclasses(compileUnit, classNode, clazz);
            makeRecordComponents(compileUnit, classNode, clazz);
            setAnnotationMetaData(clazz.getAnnotations(), classNode);

            PackageNode packageNode = classNode.getPackage();
            if (packageNode != null) {
                setAnnotationMetaData(clazz.getPackage().getAnnotations(), packageNode);
            }
        } catch (NoClassDefFoundError e) {
            throw new NoClassDefFoundError("Unable to load class " + classNode.toString(false) + " due to missing dependency " + e.getMessage());
        } catch (MalformedParameterizedTypeException e) {
            throw new RuntimeException("Unable to configure class node for class " + classNode.toString(false) + " due to malformed parameterized types", e);
        }
    }

    /**
     * Synthetic parameters such as those added for inner class constructors may
     * not be included in the parameter annotations array. This is the case when
     * at least one parameter of an inner class constructor has an annotation with
     * a RUNTIME retention (this occurs for JDK8 and below). This method will
     * normalize the annotations array so that it contains the same number of
     * elements as the array returned from {@link Constructor#getParameterTypes()}.
     *
     * If adjustment is required, the adjusted array will be prepended with a
     * zero-length element. If no adjustment is required, the original array
     * from {@link Constructor#getParameterAnnotations()} will be returned.
     *
     * @param constructor the Constructor for which to return parameter annotations
     * @return array of arrays containing the annotations on the parameters of the given Constructor
     */
    private Annotation[][] getConstructorParameterAnnotations(final Constructor<?> constructor) {
        /*
         * TODO: Remove after JDK9 is the minimum JDK supported
         *
         * JDK9+ correctly accounts for the synthetic parameter and when it becomes
         * the minimum version this method should no longer be required.
         */
        int parameterCount = constructor.getParameterTypes().length;
        Annotation[][] annotations = constructor.getParameterAnnotations();
        int diff = parameterCount - annotations.length;
        if (diff > 0) {
            // May happen on JDK8 and below. We add elements to the front of the array to account for the synthetic params:
            // - for an inner class we expect one param to account for the synthetic outer reference
            // - for an enum we expect two params to account for the synthetic name and ordinal
            if ((!constructor.getDeclaringClass().isEnum() && diff > 1) || diff > 2) {
                throw new GroovyBugError(
                        "Constructor parameter annotations length [" + annotations.length + "] " +
                        "does not match the parameter length: " + constructor
                );
            }
            Annotation[][] adjusted = new Annotation[parameterCount][];
            for (int i = 0; i < diff; i += 1) {
                adjusted[i] = EMPTY_ANNOTATION_ARRAY;
            }
            System.arraycopy(annotations, 0, adjusted, diff, annotations.length);
            return adjusted;
        }
        return annotations;
    }

    private void makePermittedSubclasses(final CompileUnit cu, final ClassNode classNode, final Class<?> clazz) {
        if (!ReflectionUtils.isSealed(clazz)) return;
        List<ClassNode> permittedSubclasses = Arrays.stream(ReflectionUtils.getPermittedSubclasses(clazz))
                .map(c -> makeClassNode(cu, c, c))
                .collect(Collectors.toList());
        classNode.setPermittedSubclasses(permittedSubclasses);
    }

    protected void makeRecordComponents(final CompileUnit cu, final ClassNode classNode, final Class<?> clazz) {
    }

    private void makeInterfaceTypes(final CompileUnit cu, final ClassNode classNode, final Class<?> clazz) {
        Type[] interfaceTypes = clazz.getGenericInterfaces();
        final int n = interfaceTypes.length;
        if (n == 0) {
            classNode.setInterfaces(ClassNode.EMPTY_ARRAY);
        } else {
            ClassNode[] ret = new ClassNode[n];
            for (int i = 0; i < n; i += 1) {
                Type type = interfaceTypes[i];
                while (!(type instanceof Class)) {
                    ParameterizedType pt = (ParameterizedType) type;
                    Type t2 = pt.getRawType();
                    if (t2 == type) {
                        throw new GroovyBugError("Cannot transform generic signature of " + clazz + " with generic interface " + interfaceTypes[i] + " to a class.");
                    }
                    type = t2;
                }
                ret[i] = makeClassNode(cu, interfaceTypes[i], (Class<?>) type);
            }
            classNode.setInterfaces(ret);
        }
    }

    private ClassNode[] makeClassNodes(final CompileUnit cu, final Type[] types, final Class<?>[] cls) {
        final int n = types.length;
        ClassNode[] nodes = new ClassNode[n];
        for (int i = 0; i < n; i += 1) {
            nodes[i] = makeClassNode(cu, types[i], cls[i]);
        }
        return nodes;
    }

    protected ClassNode makeClassNode(final CompileUnit cu, final Type t, final Class<?> c) {
        ClassNode back = null;
        if (cu != null) back = cu.getClass(c.getName());
        if (back == null) back = ClassHelper.make(c);
        if (!(t instanceof Class)) {
            ClassNode front = configureType(t);
            front.setRedirect(back);
            return front;
        }
        return back.getPlainNodeReference();
    }

    private Parameter[] makeParameters(final CompileUnit cu, final Type[] types, final Class<?>[] cls, final Annotation[][] parameterAnnotations, final java.lang.reflect.Member member) {
        Parameter[] params = Parameter.EMPTY_ARRAY;
        final int n = types.length;
        if (n > 0) {
            params = new Parameter[n];
            String[] names = new String[n];
            fillParameterNames(names, member);
            for (int i = 0; i < n; i += 1) {
                setAnnotationMetaData(parameterAnnotations[i],
                        params[i] = new Parameter(makeClassNode(cu, types[i], cls[i]), names[i]));
            }
        }
        return params;
    }

    protected void fillParameterNames(final String[] names, final java.lang.reflect.Member member) {
        try {
            java.lang.reflect.Parameter[] parameters = ((java.lang.reflect.Executable) member).getParameters();
            for (int i = 0, n = names.length; i < n; i += 1) {
                names[i] = parameters[i].getName();
            }
        } catch (RuntimeException e) {
            throw new GroovyBugError(e);
        }
    }

    /**
     * The following scenarios can not set accessible, i.e. the return value is false
     * 1) SecurityException occurred
     * 2) the accessible object is a Constructor object for the Class class
     *
     * @param accessibleObject the accessible object to check
     * @param callerClass the callerClass to invoke {@code setAccessible}
     * @return the check result
     */
    @Override
    @SuppressWarnings("removal") // TODO a future Groovy version should skip the permission check
    public boolean checkCanSetAccessible(final AccessibleObject accessibleObject, final Class<?> callerClass) {
        SecurityManager sm = System.getSecurityManager();
        try {
            if (sm != null) {
                sm.checkPermission(ACCESS_PERMISSION);
            }
        } catch (SecurityException e) {
            return false;
        }

        if (accessibleObject instanceof Constructor) {
            Constructor<?> c = (Constructor<?>) accessibleObject;
            if (c.getDeclaringClass() == Class.class) {
                return false; // Cannot make a java.lang.Class constructor accessible
            }
        }

        return true;
    }

    @Override
    public boolean checkAccessible(final Class<?> callerClass, final Class<?> declaringClass, final int memberModifiers, final boolean allowIllegalAccess) {
        return true;
    }

    @Override
    public boolean trySetAccessible(final AccessibleObject ao) {
        try {
            ao.setAccessible(true);
            return true;
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public MetaMethod transformMetaMethod(final MetaClass metaClass, final MetaMethod metaMethod, final Class<?> caller) {
        return metaMethod;
    }

    @Override
    @Deprecated
    @SuppressWarnings("removal") // TODO a future Groovy version will remove this method
    public <T> T doPrivileged(java.security.PrivilegedAction<T> action) {
        throw new UnsupportedOperationException("doPrivileged is no longer supported");
    }

    @Override
    @Deprecated
    @SuppressWarnings("removal") // TODO a future Groovy version will remove this method
    public <T> T doPrivileged(java.security.PrivilegedExceptionAction<T> action) throws java.security.PrivilegedActionException {
        throw new UnsupportedOperationException("doPrivileged is no longer supported");
    }

    @Override
    public MetaMethod transformMetaMethod(final MetaClass metaClass, final MetaMethod metaMethod) {
        return transformMetaMethod(metaClass, metaMethod, null);
    }

    @Override
    public void invalidateCallSites() {
        IndyInterface.invalidateSwitchPoints();
    }

    protected MethodHandles.Lookup getLookup(final Object receiver) {
        Optional<MethodHandles.Lookup> lookup = Optional.empty();
        if (receiver instanceof GroovyObject) {
            lookup = GroovyObjectHelper.lookup((GroovyObject) receiver);
        }
        return lookup.orElseGet(() -> newLookup(receiver.getClass()));
    }

    @Override
    public Object getInvokeSpecialHandle(final Method method, final Object receiver) {
        try {
            return getLookup(receiver)
                    .unreflectSpecial(method, receiver.getClass())
                    .bindTo(receiver);
        } catch (ReflectiveOperationException e) {
            return getInvokeSpecialHandleFallback(method, receiver);
        }
    }

    private Object getInvokeSpecialHandleFallback(final Method method, final Object receiver) {
        if (!method.isAccessible()) {
            doPrivilegedInternal(() -> {
                ReflectionUtils.trySetAccessible(method);
                return null;
            });
        }
        Class<?> declaringClass = method.getDeclaringClass();
        try {
            return newLookup(declaringClass).unreflectSpecial(method, declaringClass).bindTo(receiver);
        } catch (ReflectiveOperationException e) {
            throw new GroovyBugError(e);
        }
    }
    @SuppressWarnings("removal") // TODO a future Groovy version should perform the operation not as a privileged action
    private static <T> T doPrivilegedInternal(java.security.PrivilegedAction<T> action) {
        return java.security.AccessController.doPrivileged(action);
    }

    @Override
    public Object invokeHandle(final Object handle, final Object[] args) throws Throwable {
        return ((MethodHandle) handle).invokeWithArguments(args);
    }

    protected MethodHandles.Lookup newLookup(final Class<?> declaringClass) {
        return of(declaringClass);
    }

    public static MethodHandles.Lookup of(final Class<?> declaringClass) {
        try {
            return LookupHolder.LOOKUP_Constructor.newInstance(declaringClass, MethodHandles.Lookup.PRIVATE).in(declaringClass);
        } catch (final IllegalAccessException | InstantiationException e) {
            throw new IllegalArgumentException(e);
        } catch (final InvocationTargetException e) {
            throw new GroovyRuntimeException(e);
        }
    }

    private static class LookupHolder {
        private static final Constructor<MethodHandles.Lookup> LOOKUP_Constructor;

        static {
            Constructor<MethodHandles.Lookup> lookup;
            try {
                lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, Integer.TYPE);
            } catch (final NoSuchMethodException e) {
                throw new IllegalStateException("Incompatible JVM", e);
            }
            try {
                if (!lookup.isAccessible()) {
                    final Constructor<MethodHandles.Lookup> finalReference = lookup;
                    doPrivilegedInternal(() -> {
                        ReflectionUtils.trySetAccessible(finalReference);
                        return null;
                    });
                }
            } catch (SecurityException ignore) {
                lookup = null;
            } catch (RuntimeException e) {
                throw e;
            }
            LOOKUP_Constructor = lookup;
        }
    }
}
