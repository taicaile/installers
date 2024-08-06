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
package org.codehaus.groovy.tools.javac;

import groovy.lang.GroovyClassLoader;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.GroovyClassVisitor;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.AnnotationConstantsVisitor;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.ASTTransformationCollectorCodeVisitor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.codehaus.groovy.control.CompilerConfiguration.MEM_STUB;

/**
 * Compilation Unit capable of compiling Java source files.
 */
public class JavaAwareCompilationUnit extends CompilationUnit {

    private final JavaStubGenerator stubGenerator;
    private final Set<String> javaSources = new HashSet<>();
    private JavaCompilerFactory compilerFactory = new JavacCompilerFactory();
    private final File generationGoal;
    private final boolean keepStubs;
    private final boolean memStubEnabled;

    public JavaAwareCompilationUnit() {
        this(null, null, null);
    }

    public JavaAwareCompilationUnit(final CompilerConfiguration configuration) {
        this(configuration, null, null);
    }

    public JavaAwareCompilationUnit(final CompilerConfiguration configuration, final GroovyClassLoader groovyClassLoader) {
        this(configuration, groovyClassLoader, null);
    }

    public JavaAwareCompilationUnit(final CompilerConfiguration configuration, final GroovyClassLoader groovyClassLoader, final GroovyClassLoader transformClassLoader) {
        super(configuration, null, groovyClassLoader, transformClassLoader);

        {
            Map<String, Object> options = this.configuration.getJointCompilationOptions();
            String sourceEncoding = this.configuration.getSourceEncoding();

            this.keepStubs = Boolean.TRUE.equals(options.get("keepStubs"));
            this.memStubEnabled = Boolean.TRUE.equals(options.get(MEM_STUB));
            this.generationGoal = memStubEnabled ? null : (File) options.get("stubDir");
            this.stubGenerator = new JavaStubGenerator(generationGoal, false, sourceEncoding);
        }

        addPhaseOperation((final SourceUnit source, final GeneratorContext context, final ClassNode classNode) -> {
            if (!javaSources.isEmpty()) {
                new JavaAwareResolveVisitor(this).startResolving(classNode, source);
                new AnnotationConstantsVisitor().visitClass(classNode, source);
            }
        }, Phases.CONVERSION);

        addPhaseOperation((final SourceUnit source, final GeneratorContext context, final ClassNode classNode) -> {
            GroovyClassVisitor visitor = new ASTTransformationCollectorCodeVisitor(source, getTransformLoader());
            visitor.visitClass(classNode);
        }, Phases.CONVERSION);

        addPhaseOperation((final SourceUnit source, final GeneratorContext context, final ClassNode classNode) -> {
            try {
                if (!javaSources.isEmpty()) stubGenerator.generateClass(classNode);
            } catch (FileNotFoundException fnfe) {
                source.addException(fnfe);
            }
        }, Phases.CONVERSION);
    }

    @Override
    public void gotoPhase(final int phase) throws CompilationFailedException {
        super.gotoPhase(phase);
        // compile Java and clean up
        if (phase == Phases.SEMANTIC_ANALYSIS && !javaSources.isEmpty()) {
            for (ModuleNode module : getAST().getModules()) {
                module.setImportsResolved(false);
            }
            try {
                addJavaCompilationUnits(stubGenerator.getJavaStubCompilationUnitSet()); // add java stubs
                JavaCompiler compiler = compilerFactory.createCompiler(configuration);
                compiler.compile(new ArrayList<>(javaSources), this);
            } finally {
                if (!keepStubs) stubGenerator.clean();
                javaSources.clear();
            }
        }
    }

    @Override
    public void configure(final CompilerConfiguration configuration) {
        super.configure(configuration);
        // GroovyClassLoader should be able to find classes compiled from java sources
        File targetDir = this.configuration.getTargetDirectory();
        if (targetDir != null) {
            final String classOutput = targetDir.getAbsolutePath();
            getClassLoader().addClasspath(classOutput);
        }
    }

    private void addJavaSource(final File file) {
        javaSources.add(file.getAbsolutePath());
    }

    @Override
    public void addSources(final String[] paths) {
        for (String path : paths) {
            addJavaOrGroovySource(new File(path));
        }
    }

    @Override
    public void addSources(final File[] files) {
        for (File file : files) {
            addJavaOrGroovySource(file);
        }
    }

    private void addJavaOrGroovySource(final File file) {
        if (file.getName().endsWith(".java")) {
            addJavaSource(file);
        } else {
            addSource(file);
        }
    }

    public JavaCompilerFactory getCompilerFactory() {
        return compilerFactory;
    }

    public void setCompilerFactory(final JavaCompilerFactory compilerFactory) {
        this.compilerFactory = compilerFactory;
    }
}
