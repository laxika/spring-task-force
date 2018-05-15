package com.morethanheroic.taskforce.executor;

import com.morethanheroic.taskforce.executor.pool.ThreadPoolCache;
import com.morethanheroic.taskforce.executor.pool.ThreadPoolCacheFactory;
import com.morethanheroic.taskforce.job.Job;
import com.morethanheroic.taskforce.task.TaskDescriptor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobExecutor {

    private final ThreadPoolCacheFactory threadPoolCacheFactory = new ThreadPoolCacheFactory();

    public void execute(final Job job) {
        final ThreadPoolCache threadPoolCache = threadPoolCacheFactory.newThreadPoolCache(job.getTaskDescriptors());

        final Executor generatorExecutor = Executors.newSingleThreadExecutor();
        final Executor sinkExecutor = Executors.newSingleThreadExecutor();

        final AtomicBoolean calculator = new AtomicBoolean(true);

        while (calculator.get()) {
            CompletableFuture<Optional<?>> completableFuture = CompletableFuture.supplyAsync(() -> {
                final Optional<?> generationResult = job.getGenerator().generate();

                if (!generationResult.isPresent()) {
                    calculator.set(false);
                }

                return generationResult;
            }, generatorExecutor);

            for (TaskDescriptor taskDescriptor : job.getTaskDescriptors()) {
                completableFuture = completableFuture.thenApplyAsync(
                        (workingItem) -> {
                            if (!workingItem.isPresent()) {
                                return Optional.empty();
                            }

                            return taskDescriptor.getTask().execute( workingItem.get());
                        },
                        threadPoolCache.getExecutor(taskDescriptor.getTask()));
            }

            completableFuture.thenAcceptAsync((workItem) ->
                    workItem.ifPresent(o -> job.getSink().consume(o)), sinkExecutor);
        }
    }
}