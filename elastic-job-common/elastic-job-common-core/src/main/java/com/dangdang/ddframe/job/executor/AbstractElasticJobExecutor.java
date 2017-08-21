/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.executor;

import com.dangdang.ddframe.job.api.ShardingContext;
import com.dangdang.ddframe.job.config.JobRootConfiguration;
import com.dangdang.ddframe.job.event.type.JobExecutionEvent;
import com.dangdang.ddframe.job.event.type.JobStatusTraceEvent.State;
import com.dangdang.ddframe.job.exception.ExceptionUtil;
import com.dangdang.ddframe.job.exception.JobExecutionEnvironmentException;
import com.dangdang.ddframe.job.exception.JobSystemException;
import com.dangdang.ddframe.job.executor.handler.ExecutorServiceHandler;
import com.dangdang.ddframe.job.executor.handler.ExecutorServiceHandlerRegistry;
import com.dangdang.ddframe.job.executor.handler.JobExceptionHandler;
import com.dangdang.ddframe.job.executor.handler.JobProperties;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * 弹性化分布式作业执行器.
 *
 * @author zhangliang
 */
@Slf4j
public abstract class AbstractElasticJobExecutor {

    /**
     * 作业门面对象
     */
    @Getter(AccessLevel.PROTECTED)
    private final JobFacade jobFacade;
    /**
     * 作业配置
     */
    @Getter(AccessLevel.PROTECTED)
    private final JobRootConfiguration jobRootConfig;
    /**
     * 作业名称
     */
    private final String jobName;
    /**
     * 作业执行线程池
     */
    private final ExecutorService executorService;
    /**
     * 作业异常处理器
     */
    private final JobExceptionHandler jobExceptionHandler;
    /**
     * 分片错误信息集合
     * key：分片序号
     */
    private final Map<Integer, String> itemErrorMessages;
    
    protected AbstractElasticJobExecutor(final JobFacade jobFacade) {
        this.jobFacade = jobFacade;
        // 加载 作业配置
        jobRootConfig = jobFacade.loadJobRootConfiguration(true);
        jobName = jobRootConfig.getTypeConfig().getCoreConfig().getJobName();
        // 获取 作业执行线程池
        executorService = ExecutorServiceHandlerRegistry.getExecutorServiceHandler(jobName, (ExecutorServiceHandler) getHandler(JobProperties.JobPropertiesEnum.EXECUTOR_SERVICE_HANDLER));
        // 获取 作业异常处理器
        jobExceptionHandler = (JobExceptionHandler) getHandler(JobProperties.JobPropertiesEnum.JOB_EXCEPTION_HANDLER);
        // 设置 分片错误信息集合
        itemErrorMessages = new ConcurrentHashMap<>(jobRootConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), 1);
    }

    /**
     * 获得【自定义】处理器
     *
     * @param jobPropertiesEnum 作业属性枚举
     * @return 处理器
     */
    private Object getHandler(final JobProperties.JobPropertiesEnum jobPropertiesEnum) {
        String handlerClassName = jobRootConfig.getTypeConfig().getCoreConfig().getJobProperties().get(jobPropertiesEnum);
        try {
            Class<?> handlerClass = Class.forName(handlerClassName);
            if (jobPropertiesEnum.getClassType().isAssignableFrom(handlerClass)) { // 必须是接口实现，才使用【自定义】
                return handlerClass.newInstance();
            }
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        } catch (final ReflectiveOperationException ex) {
            return getDefaultHandler(jobPropertiesEnum, handlerClassName);
        }
    }

    /**
     * 获得【默认】处理器
     *
     * @param jobPropertiesEnum 作业属性枚举
     * @param handlerClassName 处理器类名
     * @return 处理器
     */
    private Object getDefaultHandler(final JobProperties.JobPropertiesEnum jobPropertiesEnum, final String handlerClassName) {
        log.warn("Cannot instantiation class '{}', use default '{}' class.", handlerClassName, jobPropertiesEnum.getKey());
        try {
            return Class.forName(jobPropertiesEnum.getDefaultValue()).newInstance();
        } catch (final ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new JobSystemException(e);
        }
    }
    
    /**
     * 执行作业.
     */
    public final void execute() {
        // 检查 作业执行环境
        try {
            jobFacade.checkJobExecutionEnvironment();
        } catch (final JobExecutionEnvironmentException cause) {
            jobExceptionHandler.handleException(jobName, cause);
        }
        // 获取 当前作业服务器的分片上下文
        ShardingContexts shardingContexts = jobFacade.getShardingContexts();
        // 发布作业状态追踪事件(State.TASK_STAGING)
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_STAGING, String.format("Job '%s' execute begin.", jobName));
        }
        // 跳过 存在运行中的被错过作业
        if (jobFacade.misfireIfRunning(shardingContexts.getShardingItemParameters().keySet())) {
            // 发布作业状态追踪事件(State.TASK_FINISHED)
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format(
                        "Previous job '%s' - shardingItems '%s' is still running, misfired job will start after previous job completed.", jobName, 
                        shardingContexts.getShardingItemParameters().keySet()));
            }
            return;
        }
        // 执行 作业执行前的方法
        try {
            jobFacade.beforeJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
        // 执行 普通触发的作业
        execute(shardingContexts, JobExecutionEvent.ExecutionSource.NORMAL_TRIGGER);
        // 执行 被跳过触发的作业
        while (jobFacade.isExecuteMisfired(shardingContexts.getShardingItemParameters().keySet())) {
            jobFacade.clearMisfire(shardingContexts.getShardingItemParameters().keySet());
            execute(shardingContexts, JobExecutionEvent.ExecutionSource.MISFIRE);
        }
        // 执行 作业失效转移
        jobFacade.failoverIfNecessary();
        // 执行 作业执行后的方法
        try {
            jobFacade.afterJobExecuted(shardingContexts);
            //CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            //CHECKSTYLE:ON
            jobExceptionHandler.handleException(jobName, cause);
        }
    }

    /**
     * 执行多个作业的分片
     *
     * @param shardingContexts 分片上下文集合
     * @param executionSource 执行来源
     */
    private void execute(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
        // 无可执行的分片，发布作业状态追踪事件(State.TASK_FINISHED)
        if (shardingContexts.getShardingItemParameters().isEmpty()) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format("Sharding item for job '%s' is empty.", jobName));
            }
            return;
        }
        // 注册作业启动信息
        jobFacade.registerJobBegin(shardingContexts);
        // 发布作业状态追踪事件(State.TASK_RUNNING)
        String taskId = shardingContexts.getTaskId();
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobStatusTraceEvent(taskId, State.TASK_RUNNING, "");
        }
        // TODO
        try {
            process(shardingContexts, executionSource);
        } finally {
            // TODO 考虑增加作业失败的状态，并且考虑如何处理作业失败的整体回路
            // 注册作业完成信息
            jobFacade.registerJobCompleted(shardingContexts);
            // 根据是否有异常，发布作业状态追踪事件(State.TASK_FINISHED / State.TASK_ERROR)
            if (itemErrorMessages.isEmpty()) {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_FINISHED, "");
                }
            } else {
                if (shardingContexts.isAllowSendJobEvent()) {
                    jobFacade.postJobStatusTraceEvent(taskId, State.TASK_ERROR, itemErrorMessages.toString());
                }
            }
        }
    }

    /**
     * 执行多个作业的分片
     *
     * @param shardingContexts 分片上下文集合
     * @param executionSource 执行来源
     */
    private void process(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
        Collection<Integer> items = shardingContexts.getShardingItemParameters().keySet();
        // 单分片，直接执行
        if (1 == items.size()) {
            int item = shardingContexts.getShardingItemParameters().keySet().iterator().next();
            JobExecutionEvent jobExecutionEvent =  new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, item);
            // 执行一个作业
            process(shardingContexts, item, jobExecutionEvent);
            return;
        }
        // 多分片，并行执行
        final CountDownLatch latch = new CountDownLatch(items.size());
        for (final int each : items) {
            final JobExecutionEvent jobExecutionEvent = new JobExecutionEvent(shardingContexts.getTaskId(), jobName, executionSource, each);
            if (executorService.isShutdown()) {
                return;
            }
            executorService.submit(new Runnable() {
                
                @Override
                public void run() {
                    try {
                        // 执行一个作业
                        process(shardingContexts, each, jobExecutionEvent);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        // 等待多分片全部完成
        try {
            latch.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行单个作业的分片
     *
     * @param shardingContexts 分片上下文集合
     * @param item 分片序号
     * @param startEvent 执行事件(开始)
     */
    private void process(final ShardingContexts shardingContexts, final int item, final JobExecutionEvent startEvent) {
        // 发布执行事件(开始)
        if (shardingContexts.isAllowSendJobEvent()) {
            jobFacade.postJobExecutionEvent(startEvent);
        }
        log.trace("Job '{}' executing, item is: '{}'.", jobName, item);
        JobExecutionEvent completeEvent;
        try {
            // 执行单个作业
            process(new ShardingContext(shardingContexts, item));
            // 发布执行事件(成功)
            completeEvent = startEvent.executionSuccess();
            log.trace("Job '{}' executed, item is: '{}'.", jobName, item);
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobExecutionEvent(completeEvent);
            }
            // CHECKSTYLE:OFF
        } catch (final Throwable cause) {
            // CHECKSTYLE:ON
            // 发布执行事件(失败)
            completeEvent = startEvent.executionFailure(cause);
            jobFacade.postJobExecutionEvent(completeEvent);
            // 设置该分片执行异常信息
            itemErrorMessages.put(item, ExceptionUtil.transform(cause));
            //
            jobExceptionHandler.handleException(jobName, cause);
        }
    }

    /**
     * 执行单个作业的分片【子类实现】
     *
     * @param shardingContext 分片上下文集合
     */
    protected abstract void process(ShardingContext shardingContext);
}
