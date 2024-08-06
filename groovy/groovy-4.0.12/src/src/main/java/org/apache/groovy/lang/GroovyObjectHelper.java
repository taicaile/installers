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
package org.apache.groovy.lang;

import groovy.lang.GroovyObject;
import org.codehaus.groovy.GroovyBugError;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper for {@link groovy.lang.GroovyObject}
 *
 * @since 4.0.0
 */
public class GroovyObjectHelper {
    /**
     * Get the {@link Lookup} instance of the {@link GroovyObject} instance
     *
     * @param groovyObject the {@link GroovyObject} instance
     * @return the {@link Lookup} instance
     * @since 4.0.0
     */
    public static Optional<Lookup> lookup(GroovyObject groovyObject) {
        final Class<? extends GroovyObject> groovyObjectClass = groovyObject.getClass();
        final AtomicReference<Lookup> lookupAtomicRef = LOOKUP_MAP.get(groovyObjectClass);
        Lookup lookup = lookupAtomicRef.get();
        if (null != lookup) {
            if (NULL_LOOKUP == lookup)
                return Optional.empty();

            return Optional.of(lookup);
        }

        if (groovyObject.getMetaClass().respondsTo(groovyObject, GET_LOOKUP_METHOD_NAME).isEmpty()) {
            lookupAtomicRef.set(NULL_LOOKUP);
            return Optional.empty();
        }

        if (groovyObjectClass.isMemberClass() && Modifier.isStatic(groovyObjectClass.getModifiers())) {
            List<Class<?>> classList = new ArrayList<>(3);
            for (Class<?> clazz = groovyObjectClass; null != clazz; clazz = clazz.getEnclosingClass()) {
                if (isNonStaticInnerClass(clazz)) {
                    return Optional.empty();
                }
                classList.add(clazz);
            }

            Lookup caller = LOOKUP;
            for (int i = classList.size() - 1; i >= 0; i--) {
                Class<?> c = classList.get(i);
                caller = doLookup(c, caller);
                if (null == caller) {
                    return Optional.empty();
                }
            }
            lookup = caller;
        } else {
            lookup = doLookup(groovyObject);
        }

        if (null != lookup) lookupAtomicRef.set(lookup);

        return Optional.ofNullable(lookup);
    }

    private static boolean isNonStaticInnerClass(Class<?> clazz) {
        return clazz.isMemberClass() && !Modifier.isStatic(clazz.getModifiers());
    }

    private static Lookup doLookup(GroovyObject groovyObject) {
        MethodHandles.Lookup lookup;
        try {
            final Class<? extends GroovyObject> groovyObjectClass = groovyObject.getClass();
            if (groovyObjectClass.isAnonymousClass() ||
                    (isNonStaticInnerClass(groovyObjectClass))) {
                lookup = (MethodHandles.Lookup) LOOKUP
                        .unreflect(findGetLookupMethod(groovyObjectClass))
                        .bindTo(groovyObject)
                        .invokeExact();
            } else {
                lookup = doLookup(groovyObjectClass);
            }
        } catch (Throwable e) {
            lookup = null;
        }

        return lookup;
    }

    private static Lookup doLookup(Class<?> groovyObjectClass) {
        return doLookup(groovyObjectClass, LOOKUP);
    }

    private static Lookup doLookup(Class<?> groovyObjectClass, Lookup caller) {
        try {
            return (Lookup) caller
                    .unreflect(findGetLookupMethod(groovyObjectClass))
                    .invokeExact();
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Method findGetLookupMethod(Class<?> groovyObjectClass) throws NoSuchMethodException {
        return groovyObjectClass.getDeclaredMethod(GET_LOOKUP_METHOD_NAME);
    }

    private GroovyObjectHelper() {}

    private static final String GET_LOOKUP_METHOD_NAME = "$getLookup";
    private static final Lookup LOOKUP = MethodHandles.lookup();
    private static final Lookup NULL_LOOKUP = MethodHandles.lookup();

    static {
        if (NULL_LOOKUP == LOOKUP) {
            // should never happen
            throw new GroovyBugError("`MethodHandles.lookup()` returns the same `Lookup` instance");
        }
    }

    private static final ClassValue<AtomicReference<Lookup>> LOOKUP_MAP = new ClassValue<AtomicReference<Lookup>>() {
        @Override
        protected AtomicReference<Lookup> computeValue(Class<?> type) {
            return new AtomicReference<>();
        }
    };
}
