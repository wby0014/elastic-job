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

package com.dangdang.ddframe.job.cloud.scheduler.mesos;

import com.dangdang.ddframe.job.api.JobType;
import com.dangdang.ddframe.job.cloud.scheduler.config.app.CloudAppConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.job.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.job.CloudJobExecutionType;
import com.dangdang.ddframe.job.cloud.scheduler.env.BootstrapEnvironment;
import com.dangdang.ddframe.job.config.script.ScriptJobConfiguration;
import com.dangdang.ddframe.job.context.ExecutionType;
import com.dangdang.ddframe.job.context.TaskContext;
import com.dangdang.ddframe.job.event.JobEventBus;
import com.dangdang.ddframe.job.event.type.JobStatusTraceEvent;
import com.dangdang.ddframe.job.event.type.JobStatusTraceEvent.Source;
import com.dangdang.ddframe.job.executor.ShardingContexts;
import com.dangdang.ddframe.job.util.config.ShardingItemParameters;
import com.dangdang.ddframe.job.util.json.GsonFactory;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.protobuf.ByteString;
import com.netflix.fenzo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.SchedulerDriver;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * 任务提交调度服务.
 * 
 * @author zhangliang
 * @author gaohongtao
 */
@RequiredArgsConstructor
@Slf4j
public final class TaskLaunchScheduledService extends AbstractScheduledService {
    
    private final SchedulerDriver schedulerDriver;
    
    private final TaskScheduler taskScheduler;
    
    private final FacadeService facadeService;
    
    private final JobEventBus jobEventBus;
    
    private final BootstrapEnvironment env = BootstrapEnvironment.getInstance();
    
    @Override
    protected String serviceName() {
        return "task-launch-processor";
    }
    
    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(2, 10, TimeUnit.SECONDS);
    }
    
    @Override
    protected void startUp() throws Exception {
        log.info("Elastic Job: Start {}", serviceName());
        AppConstraintEvaluator.init(facadeService);
    }
    
    @Override
    protected void shutDown() throws Exception {
        log.info("Elastic Job: Stop {}", serviceName());
    }
    
    @Override
    protected void runOneIteration() throws Exception {
        try {
//            System.out.println("runOneIteration:" + new Date());
            // 创建 Fenzo 任务请求
            LaunchingTasks launchingTasks = new LaunchingTasks(facadeService.getEligibleJobContext());
            List<TaskRequest> taskRequests = launchingTasks.getPendingTasks();
            // 获取所有正在运行的云作业App https://github.com/Netflix/Fenzo/wiki/Constraints
            if (!taskRequests.isEmpty()) {
                AppConstraintEvaluator.getInstance().loadAppRunningState();
            }
            // 将任务请求分配到 Mesos Offer
            Collection<VMAssignmentResult> vmAssignmentResults = taskScheduler.scheduleOnce(taskRequests, LeasesQueue.getInstance().drainTo()).getResultMap().values();
            // 创建 Mesos 任务请求
            List<TaskContext> taskContextsList = new LinkedList<>(); // 任务运行时上下文集合
            Map<List<Protos.OfferID>, List<Protos.TaskInfo>> offerIdTaskInfoMap = new HashMap<>(); // Mesos 任务信息集合
            for (VMAssignmentResult each: vmAssignmentResults) {
                List<VirtualMachineLease> leasesUsed = each.getLeasesUsed();
                List<Protos.TaskInfo> taskInfoList = new ArrayList<>(each.getTasksAssigned().size() * 10);
                taskInfoList.addAll(getTaskInfoList(
                        launchingTasks.getIntegrityViolationJobs(vmAssignmentResults), // 获得作业分片不完整的作业集合
                        each, leasesUsed.get(0).hostname(), leasesUsed.get(0).getOffer()));
                for (Protos.TaskInfo taskInfo : taskInfoList) {
                    taskContextsList.add(TaskContext.from(taskInfo.getTaskId().getValue()));
                }
                offerIdTaskInfoMap.put(getOfferIDs(leasesUsed), // 获得 Offer ID 集合
                        taskInfoList);
            }
            // 遍历任务运行时上下文
            for (TaskContext each : taskContextsList) {
                // 将任务运行时上下文放入运行时队列
                facadeService.addRunning(each);
                // 发布作业状态追踪事件(State.TASK_STAGING)
                jobEventBus.post(createJobStatusTraceEvent(each));
            }
            // 从队列中删除已运行的作业
            facadeService.removeLaunchTasksFromQueue(taskContextsList);
            // 提交任务给 Mesos
            for (Entry<List<OfferID>, List<TaskInfo>> each : offerIdTaskInfoMap.entrySet()) {
                schedulerDriver.launchTasks(each.getKey(), each.getValue());
            }
            //CHECKSTYLE:OFF
        } catch (Throwable throwable) {
            //CHECKSTYLE:ON
            log.error("Launch task error", throwable);
        } finally {
            // 清理 AppConstraintEvaluator 所有正在运行的云作业App
            AppConstraintEvaluator.getInstance().clearAppRunningState();
        }
    }
    
    private List<Protos.TaskInfo> getTaskInfoList(final Collection<String> integrityViolationJobs, final VMAssignmentResult vmAssignmentResult, final String hostname, final Protos.Offer offer) {
        List<Protos.TaskInfo> result = new ArrayList<>(vmAssignmentResult.getTasksAssigned().size());
        for (TaskAssignmentResult each: vmAssignmentResult.getTasksAssigned()) {
            TaskContext taskContext = TaskContext.from(each.getTaskId());
            String jobName = taskContext.getMetaInfo().getJobName();
            if (!integrityViolationJobs.contains(jobName) // 排除作业分片不完整的任务
                    && !facadeService.isRunning(taskContext) // 排除正在运行中的任务
                    && !facadeService.isJobDisabled(jobName)) { // 排除被禁用的任务
                // 创建 Mesos 任务
                Protos.TaskInfo taskInfo = getTaskInfo(offer, each);
                if (null != taskInfo) {
                    result.add(taskInfo);
                    // 添加任务主键和主机名称的映射
                    facadeService.addMapping(taskInfo.getTaskId().getValue(), hostname);
                    // 通知 TaskScheduler 主机分配了这个任务
                    taskScheduler.getTaskAssigner().call(each.getRequest(), hostname);
                }
            }
        }
        return result;
    }
    
    private Protos.TaskInfo getTaskInfo(final Protos.Offer offer, final TaskAssignmentResult taskAssignmentResult) {
        // 校验 作业配置 是否存在
        TaskContext taskContext = TaskContext.from(taskAssignmentResult.getTaskId());
        Optional<CloudJobConfiguration> jobConfigOptional = facadeService.load(taskContext.getMetaInfo().getJobName());
        if (!jobConfigOptional.isPresent()) {
            return null;
        }
        CloudJobConfiguration jobConfig = jobConfigOptional.get();
        // 校验 作业配置 是否存在
        Optional<CloudAppConfiguration> appConfigOptional = facadeService.loadAppConfig(jobConfig.getAppName());
        if (!appConfigOptional.isPresent()) {
            return null;
        }
        CloudAppConfiguration appConfig = appConfigOptional.get();
        // 设置 Mesos Slave ID
        taskContext.setSlaveId(offer.getSlaveId().getValue());
        // 获得 分片上下文集合
        ShardingContexts shardingContexts = getShardingContexts(taskContext, appConfig, jobConfig);
        // 瞬时的脚本作业，使用 Mesos 命令行执行，无需使用执行器
        boolean isCommandExecutor = CloudJobExecutionType.TRANSIENT == jobConfig.getJobExecutionType() && JobType.SCRIPT == jobConfig.getTypeConfig().getJobType();
        String script = appConfig.getBootstrapScript();
        if (isCommandExecutor) {
            script = ((ScriptJobConfiguration) jobConfig.getTypeConfig()).getScriptCommandLine();
        }
        // 创建 启动命令
        Protos.CommandInfo.URI uri = buildURI(appConfig, isCommandExecutor);
        Protos.CommandInfo command = buildCommand(uri, script, shardingContexts, isCommandExecutor);
        // 创建 Mesos 任务信息
        if (isCommandExecutor) {
            return buildCommandExecutorTaskInfo(taskContext, jobConfig, shardingContexts, offer, command);
        } else {
            return buildCustomizedExecutorTaskInfo(taskContext, appConfig, jobConfig, shardingContexts, offer, command);
        }
    }
    
    private ShardingContexts getShardingContexts(final TaskContext taskContext, final CloudAppConfiguration appConfig, final CloudJobConfiguration jobConfig) {
        Map<Integer, String> shardingItemParameters = new ShardingItemParameters(jobConfig.getTypeConfig().getCoreConfig().getShardingItemParameters()).getMap();
        Map<Integer, String> assignedShardingItemParameters = new HashMap<>(1, 1);
        int shardingItem = taskContext.getMetaInfo().getShardingItems().get(0); // 单个作业分片
        assignedShardingItemParameters.put(shardingItem, shardingItemParameters.containsKey(shardingItem) ? shardingItemParameters.get(shardingItem) : "");
        return new ShardingContexts(taskContext.getId(), jobConfig.getJobName(), jobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(),
                jobConfig.getTypeConfig().getCoreConfig().getJobParameter(), assignedShardingItemParameters, appConfig.getEventTraceSamplingCount());
    }
    
    private Protos.TaskInfo buildCommandExecutorTaskInfo(final TaskContext taskContext, final CloudJobConfiguration jobConfig, final ShardingContexts shardingContexts,
                                                         final Protos.Offer offer, final Protos.CommandInfo command) {
        Protos.TaskInfo.Builder result = Protos.TaskInfo.newBuilder().setTaskId(Protos.TaskID.newBuilder().setValue(taskContext.getId()).build())
                .setName(taskContext.getTaskName()).setSlaveId(offer.getSlaveId())
                .addResources(buildResource("cpus", jobConfig.getCpuCount(), offer.getResourcesList()))
                .addResources(buildResource("mem", jobConfig.getMemoryMB(), offer.getResourcesList()))
                .setData(ByteString.copyFrom(new TaskInfoData(shardingContexts, jobConfig).serialize())); //
        return result.setCommand(command).build();
    }
    
    private Protos.TaskInfo buildCustomizedExecutorTaskInfo(final TaskContext taskContext, final CloudAppConfiguration appConfig, final CloudJobConfiguration jobConfig, 
                                                            final ShardingContexts shardingContexts, final Protos.Offer offer, final Protos.CommandInfo command) {
        Protos.TaskInfo.Builder result = Protos.TaskInfo.newBuilder().setTaskId(Protos.TaskID.newBuilder().setValue(taskContext.getId()).build())
                .setName(taskContext.getTaskName()).setSlaveId(offer.getSlaveId())
                .addResources(buildResource("cpus", jobConfig.getCpuCount(), offer.getResourcesList()))
                .addResources(buildResource("mem", jobConfig.getMemoryMB(), offer.getResourcesList()))
                .setData(ByteString.copyFrom(new TaskInfoData(shardingContexts, jobConfig).serialize()));
        // ExecutorInfo
        Protos.ExecutorInfo.Builder executorBuilder = Protos.ExecutorInfo.newBuilder().setExecutorId(Protos.ExecutorID.newBuilder()
                .setValue(taskContext.getExecutorId(jobConfig.getAppName()))) // 执行器 ID
                .setCommand(command)
                .addResources(buildResource("cpus", appConfig.getCpuCount(), offer.getResourcesList()))
                .addResources(buildResource("mem", appConfig.getMemoryMB(), offer.getResourcesList()));
        if (env.getJobEventRdbConfiguration().isPresent()) {
            executorBuilder.setData(ByteString.copyFrom(SerializationUtils.serialize(env.getJobEventRdbConfigurationMap()))).build();
        }
        return result.setExecutor(executorBuilder.build()).build();
    }
    
    private Protos.CommandInfo.URI buildURI(final CloudAppConfiguration appConfig, final boolean isCommandExecutor) {
        Protos.CommandInfo.URI.Builder result = Protos.CommandInfo.URI.newBuilder()
                .setValue(appConfig.getAppURL())
                .setCache(appConfig.isAppCacheEnable()); // cache
        if (isCommandExecutor && !SupportedExtractionType.isExtraction(appConfig.getAppURL())) {
            result.setExecutable(true); // 是否可执行
        } else {
            result.setExtract(true); // 是否需要解压
        }
        return result.build();
    }
    
    private Protos.CommandInfo buildCommand(final Protos.CommandInfo.URI uri, final String script, final ShardingContexts shardingContexts, final boolean isCommandExecutor) {
        Protos.CommandInfo.Builder result = Protos.CommandInfo.newBuilder().addUris(uri).setShell(true);
        if (isCommandExecutor) {
            CommandLine commandLine = CommandLine.parse(script);
            commandLine.addArgument(GsonFactory.getGson().toJson(shardingContexts), false);
            result.setValue(Joiner.on(" ").join(commandLine.getExecutable(), Joiner.on(" ").join(commandLine.getArguments())));
        } else {
            result.setValue(script);
        }
        return result.build();
    }
    
    private Protos.Resource buildResource(final String type, final double resourceValue, final List<Protos.Resource> resources) {
        return Protos.Resource.newBuilder().mergeFrom(Iterables.find(resources, new Predicate<Protos.Resource>() {
            @Override
            public boolean apply(final Protos.Resource input) {
                return input.getName().equals(type);
            }
        })).setScalar(Protos.Value.Scalar.newBuilder().setValue(resourceValue)).build();
    }
    
    private JobStatusTraceEvent createJobStatusTraceEvent(final TaskContext taskContext) {
        TaskContext.MetaInfo metaInfo = taskContext.getMetaInfo();
        JobStatusTraceEvent result = new JobStatusTraceEvent(metaInfo.getJobName(), taskContext.getId(), taskContext.getSlaveId(),
                Source.CLOUD_SCHEDULER, taskContext.getType(), String.valueOf(metaInfo.getShardingItems()), JobStatusTraceEvent.State.TASK_STAGING, "");
        if (ExecutionType.FAILOVER == taskContext.getType()) {
            Optional<String> taskContextOptional = facadeService.getFailoverTaskId(metaInfo);
            if (taskContextOptional.isPresent()) {
                result.setOriginalTaskId(taskContextOptional.get());
            }
        }
        return result;
    }
    
    private List<Protos.OfferID> getOfferIDs(final List<VirtualMachineLease> leasesUsed) {
        List<Protos.OfferID> result = new ArrayList<>();
        for (VirtualMachineLease virtualMachineLease: leasesUsed) {
            result.add(virtualMachineLease.getOffer().getId());
        }
        return result;
    }
}
