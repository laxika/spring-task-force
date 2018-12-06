package com.morethanheroic.taskforce.executor;

import com.morethanheroic.taskforce.executor.domain.JobExecutionContext;
import com.morethanheroic.taskforce.job.Job;
import com.morethanheroic.taskforce.task.domain.TaskDescriptor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class JobExecutor {

    public void execute(final Job job) {
        execute(JobExecutionContext.builder().build(), job);
    }

    public void execute(final JobExecutionContext jobExecutionContext, final Job job) {
        final Semaphore semaphore = new Semaphore(jobExecutionContext.getPreparedTaskCount());

        final ExecutorService generatorExecutor = Executors.newSingleThreadExecutor();
        final ExecutorService sinkExecutor = Executors.newSingleThreadExecutor();

        final AtomicBoolean calculator = new AtomicBoolean(true);

        while (calculator.get()) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

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
                            //Skip empty working items
                            if (!workingItem.isPresent()) {
                                return Optional.empty();
                            }

                            return taskDescriptor.getTask().execute(workingItem.get());
                        },
                        taskDescriptor.getExecutor());
            }

            completableFuture.thenAcceptAsync((workItem) -> {
                workItem.ifPresent(o -> {
                    try {
                        job.getSink().consume(o);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                semaphore.release();
            }, sinkExecutor);

            completableFuture.exceptionally((e) -> {
                log.error("Error while executing a job!", e);

                semaphore.release();

                return Optional.empty();
            });
        }

        // Cleaning up the executor services.
        try {
            semaphore.acquire(jobExecutionContext.getPreparedTaskCount());

            job.getSink().cleanup();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            generatorExecutor.shutdown();
            sinkExecutor.shutdown();

            job.getTaskDescriptors()
                    .forEach(taskDescriptor -> taskDescriptor.getExecutor().shutdown());
        }
    }
}
