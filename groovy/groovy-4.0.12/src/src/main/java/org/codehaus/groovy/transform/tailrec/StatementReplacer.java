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
package org.codehaus.groovy.transform.tailrec;

import groovy.lang.Closure;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tool for replacing Statement objects in an AST by other Statement instances.
 * <p>
 * Within @TailRecursive it is used to swap ReturnStatements with looping back to RECUR label
 */
public class StatementReplacer extends CodeVisitorSupport {
    public StatementReplacer(Closure<Boolean> when, Closure<Statement> replaceWith) {
        this.when = when;
        this.replaceWith = replaceWith;
    }

    public void replaceIn(ASTNode root) {
        root.visit(this);
    }

    public void visitClosureExpression(ClosureExpression expression) {
        closureLevel++;
        try {
            super.visitClosureExpression(expression);
        } finally {
            closureLevel--;
        }
    }

    public void visitBlockStatement(final BlockStatement block) {
        List<Statement> copyOfStatements = new ArrayList<>(block.getStatements());
        for (int i = 0, n = copyOfStatements.size(); i < n; i++) {
            final int index = i;
            Statement statement = copyOfStatements.get(index);
            replaceIfNecessary(statement, (Statement node) -> block.getStatements().set(index, node));
        }
        super.visitBlockStatement(block);
    }

    public void visitIfElse(final IfStatement ifElse) {
        replaceIfNecessary(ifElse.getIfBlock(), ifElse::setIfBlock);
        replaceIfNecessary(ifElse.getElseBlock(), ifElse::setElseBlock);
        super.visitIfElse(ifElse);
    }

    public void visitForLoop(final ForStatement forLoop) {
        replaceIfNecessary(forLoop.getLoopBlock(), forLoop::setLoopBlock);
        super.visitForLoop(forLoop);
    }

    public void visitWhileLoop(final WhileStatement loop) {
        replaceIfNecessary(loop.getLoopBlock(), loop::setLoopBlock);
        super.visitWhileLoop(loop);
    }

    public void visitDoWhileLoop(final DoWhileStatement loop) {
        replaceIfNecessary(loop.getLoopBlock(), loop::setLoopBlock);
        super.visitDoWhileLoop(loop);
    }

    private void replaceIfNecessary(Statement nodeToCheck, Consumer<? super Statement> replacementCode) {
        if (conditionFulfilled(nodeToCheck)) {
            Statement replacement = replaceWith.call(nodeToCheck);
            replacement.setSourcePosition(nodeToCheck);
            replacement.copyNodeMetaData(nodeToCheck);
            replacementCode.accept(replacement);
        }
    }

    private boolean conditionFulfilled(ASTNode nodeToCheck) {
        if (when.getMaximumNumberOfParameters() < 2) return when.call(nodeToCheck);
        return when.call(nodeToCheck, isInClosure());
    }

    private boolean isInClosure() {
        return closureLevel > 0;
    }

    public Closure<Boolean> getWhen() {
        return when;
    }

    public void setWhen(Closure<Boolean> when) {
        this.when = when;
    }

    public Closure<Statement> getReplaceWith() {
        return replaceWith;
    }

    public void setReplaceWith(Closure<Statement> replaceWith) {
        this.replaceWith = replaceWith;
    }

    public int getClosureLevel() {
        return closureLevel;
    }

    public void setClosureLevel(int closureLevel) {
        this.closureLevel = closureLevel;
    }

    private Closure<Boolean> when;
    private Closure<Statement> replaceWith;
    private int closureLevel = 0;
}
