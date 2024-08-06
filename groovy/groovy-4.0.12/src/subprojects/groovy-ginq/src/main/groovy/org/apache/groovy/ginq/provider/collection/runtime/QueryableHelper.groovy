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
package org.apache.groovy.ginq.provider.collection.runtime

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.Internal
import org.apache.groovy.util.SystemUtil

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.TimeUnit
import java.util.function.Function
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.Logger
import java.util.stream.Collectors

import static org.apache.groovy.ginq.provider.collection.runtime.Queryable.from
/**
 * Helper for {@link Queryable}
 *
 * @since 4.0.0
 */
@Internal
@CompileStatic
class QueryableHelper {
    /**
     * Make {@link Queryable} instance's data source records being able to access via aliases
     *
     * @param queryable the original {@link Queryable} instance
     * @param aliasList the aliases of clause {@code from} and joins
     * @return the result {@link Queryable} instance
     * @since 4.0.0
     */
    static <T> Queryable<SourceRecord<T>> navigate(Queryable<? extends T> queryable, List<String> aliasList) {
        List<SourceRecord<T>> sourceRecordList =
                queryable.stream()
                        .map(e -> new SourceRecord<T>(e, aliasList))
                        .collect(Collectors.toList())

        return from(sourceRecordList)
    }

    /**
     * Returns single value of {@link Queryable} instance
     *
     * @param queryable the {@link Queryable} instance
     * @return the single value
     * @throws TooManyValuesException if the {@link Queryable} instance contains more than one value
     * @since 4.0.0
     */
    static <T> T singleValue(final Queryable<? extends T> queryable) {
        List<? extends T> list = queryable.toList()
        int size = list.size()

        if (0 == size) {
            return null
        }
        if (1 == size) {
            return list.get(0)
        }

        throw new TooManyValuesException("subquery returns more than one value: $list")
    }

    static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return CompletableFuture.supplyAsync(supplier, ThreadPoolHolder.THREAD_POOL)
    }

    static <T, U> CompletableFuture<U> supplyAsync(Function<? super T, ? extends U> function, T param) {
        return CompletableFuture.supplyAsync(() -> { function.apply(param) }, ThreadPoolHolder.THREAD_POOL)
    }

    static <T> ForkJoinTask<T> submit(Callable<T> callable) {
        return ThreadPoolHolder.FORKJOIN_POOL.submit(callable)
    }

    static boolean isParallel() {
        return TRUE_STR == getVar(PARALLEL)
    }

    static <T> void setVar(String name, T value) {
        VAR_HOLDER.get().put(name, value)
    }

    static <T> T getVar(String name) {
        (T) VAR_HOLDER.get().get(name)
    }

    static <T> T removeVar(String name) {
        (T) VAR_HOLDER.get().remove(name)
    }

    /**
     * Shutdown to release resources
     *
     * @param mode 0: immediate, 1: abort
     */
    static void shutdown(int mode) {
        if (0 == mode) {
            ThreadPoolHolder.FORKJOIN_POOL.shutdown()
            ThreadPoolHolder.THREAD_POOL.shutdown()

            while (!ThreadPoolHolder.FORKJOIN_POOL.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                // do nothing, just wait to terminate
            }
            while (!ThreadPoolHolder.THREAD_POOL.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                // do nothing, just wait to terminate
            }
        } else if (1 == mode) {
            ThreadPoolHolder.FORKJOIN_POOL.shutdownNow()
            ThreadPoolHolder.THREAD_POOL.shutdownNow()
        } else {
            throw new IllegalArgumentException("Invalid mode: $mode")
        }
    }

    private static final ThreadLocal<Map<String, Object>> VAR_HOLDER = InheritableThreadLocal.<Map<String, Object>> withInitial(() -> new LinkedHashMap<>())
    private static final String PARALLEL = "parallel"
    private static final String TRUE_STR = "true"

    private QueryableHelper() {}

    private static class ThreadPoolHolder {
        private static final Logger LOGGER = Logger.getLogger(ThreadPoolHolder.class.getName());
        static int fjSeq
        static int seq
        static final int PARALLELISM = SystemUtil.getIntegerSafe("groovy.ginq.parallelism", Runtime.getRuntime().availableProcessors() + 1);
        static final ForkJoinPool FORKJOIN_POOL = new ForkJoinPool(PARALLELISM, (ForkJoinPool pool) -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
            worker.setName("ginq-fj-thread-${fjSeq++}")
            return worker
        }, null, false)
        static final ExecutorService THREAD_POOL = createExecutorService()

        @CompileDynamic
        private static ExecutorService createExecutorService() {
            var threadPool
            try {
                threadPool = Executors.newVirtualThreadPerTaskExecutor()
            } catch (e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Failed to create VirtualThreadPerTaskExecutor.\n${e.asString()}")
                }
                threadPool = Executors.newFixedThreadPool(PARALLELISM, (Runnable r) -> {
                    Thread t = new Thread(r)
                    t.setName("ginq-thread-${seq++}")
                    t.setDaemon(true)
                    return t
                })
            }
            return threadPool
        }

        private ThreadPoolHolder() {}
    }
}
