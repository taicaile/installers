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
package org.apache.groovy.groovysh

import groovy.transform.AutoFinal
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import jline.console.ConsoleReader
import jline.console.completer.AggregateCompleter
import jline.console.completer.CandidateListCompletionHandler
import jline.console.completer.Completer
import jline.console.completer.CompletionHandler
import jline.console.history.FileHistory
import org.apache.groovy.groovysh.completion.FileNameCompleter
import org.apache.groovy.groovysh.util.WrappedInputStream
import org.codehaus.groovy.tools.shell.IO
import org.codehaus.groovy.tools.shell.util.Logger
import org.codehaus.groovy.tools.shell.util.Preferences

/**
 * Support for running a {@link Shell} interactively using the JLine library.
 */
@AutoFinal @CompileStatic
class InteractiveShellRunner extends ShellRunner implements Runnable {

    ConsoleReader reader
    final Closure prompt
    final CommandsMultiCompleter completer
    WrappedInputStream  wrappedInputStream

    @CompileDynamic
    InteractiveShellRunner(Groovysh shell, Closure prompt) {
        super(shell)

        this.prompt = prompt
        this.wrappedInputStream = new WrappedInputStream(shell.io.inputStream)
        this.reader = new ConsoleReader(wrappedInputStream, shell.io.outputStream)

        CompletionHandler currentCompletionHandler = reader.getCompletionHandler()
        if (currentCompletionHandler instanceof CandidateListCompletionHandler) {
            currentCompletionHandler.setStripAnsi(true)
            currentCompletionHandler.setPrintSpaceAfterFullCompletion(false)
        }

        // expand events ia an advanced feature of JLine that clashes with Groovy syntax (e.g. invoke "2!=3")
        reader.expandEvents = false

        // complete groovysh commands, display, import, ... as first word in line
        completer = new CommandsMultiCompleter()

        def reflectionCompleter = new org.apache.groovy.groovysh.completion.antlr4.ReflectionCompleter(shell)

        def classnameCompleter = new org.apache.groovy.groovysh.completion.antlr4.CustomClassSyntaxCompleter(shell)

        List<org.apache.groovy.groovysh.completion.antlr4.IdentifierCompleter> identifierCompleters = [
            new org.apache.groovy.groovysh.completion.antlr4.KeywordSyntaxCompleter(),
            new org.apache.groovy.groovysh.completion.antlr4.VariableSyntaxCompleter(shell),
            classnameCompleter,
            new org.apache.groovy.groovysh.completion.antlr4.ImportsSyntaxCompleter(shell)
        ]

        def filenameCompleter = new FileNameCompleter(false)

        reader.addCompleter(completer)
        reader.addCompleter(new org.apache.groovy.groovysh.completion.antlr4.GroovySyntaxCompleter(
                shell, reflectionCompleter, classnameCompleter, identifierCompleters, filenameCompleter))
    }

    @Override
    void run() {
        for (command in shell.registry.commands()) {
            completer.add(command)
        }

        // Force things to become clean
        completer.refresh()

        // And then actually run
        adjustHistory()
        super.run()
    }

    @Override @CompileDynamic
    protected String readLine() {
        try {
            if (Boolean.valueOf(Preferences.get(Groovysh.AUTOINDENT_PREFERENCE_KEY))) {
                // prevent auto-indent when pasting code blocks
                if (shell.io.inputStream.available() == 0) {
                    wrappedInputStream.insert(((Groovysh) shell).indentPrefix)
                }
            }
            return reader.readLine(prompt.call() as String)
        } catch (StringIndexOutOfBoundsException e) {
            log.debug('HACK: Try and work around GROOVY-2152 for now', e)
            reader.println()
            return ''
        } catch (Throwable t) {
            if (shell.io.verbosity == IO.Verbosity.DEBUG) {
                throw t
            }
            reader.println()
            return ''
        }
    }

    @Override
    protected boolean work() {
        boolean result = super.work()
        adjustHistory()

        result
    }

    private void adjustHistory() {
        // we save the evicted line in case someone wants to use it with history recall
        if (shell instanceof Groovysh) {
            def history = shell.history
            shell.historyFull = history != null && history.size() >= history.maxSize
            if (shell.isHistoryFull()) {
                def first = history.first()
                if (first) {
                    shell.evictedLine = first.value()
                }
            }
        }
    }

    void setHistory(FileHistory history) {
        reader.history = history
        def dir = history.file.parentFile

        if (!dir.exists()) {
            dir.mkdirs()

            log.debug("Created base directory for history file: $dir")
        }

        log.debug("Using history file: $history.file")
    }
}

/**
 * Completer for interactive shells.
 */
@AutoFinal @CompileStatic
class CommandsMultiCompleter extends AggregateCompleter {

    protected final Logger log = Logger.create(getClass())

    List<Completer> list = []

    private boolean dirty

    def add(Command command) {
        assert command

        //
        // FIXME: Need to handle completer removal when things like aliases are rebound
        //

        def c = command.completer

        if (c) {
            list << c

            log.debug("Added completer[${list.size()}] for command: $command.name")

            dirty = true
        }
    }

    void refresh() {
        log.debug('Refreshing the completer list')

        completers.clear()
        completers.addAll(list)
        dirty = false
    }

    @Override
    int complete(String buffer, int pos, List cand) {
        assert buffer != null

        //
        // FIXME: This is a bit of a hack, I'm too lazy to rewrite a more efficient
        //        completer impl that is more dynamic than the jline.MultiCompleter version
        //        so just re-use it and reset the list as needed
        //

        if (dirty) {
            refresh()
        }

        return super.complete(buffer, pos, cand)
    }
}
