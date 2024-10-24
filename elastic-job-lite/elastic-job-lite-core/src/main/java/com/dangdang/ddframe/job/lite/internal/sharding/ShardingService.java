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

package com.dangdang.ddframe.job.lite.internal.sharding;

import com.dangdang.ddframe.job.lite.api.strategy.JobInstance;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingStrategy;
import com.dangdang.ddframe.job.lite.api.strategy.JobShardingStrategyFactory;
import com.dangdang.ddframe.job.lite.config.LiteJobConfiguration;
import com.dangdang.ddframe.job.lite.internal.config.ConfigurationService;
import com.dangdang.ddframe.job.lite.internal.election.LeaderService;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceNode;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceService;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.server.ServerService;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodePath;
import com.dangdang.ddframe.job.lite.internal.storage.JobNodeStorage;
import com.dangdang.ddframe.job.lite.internal.storage.TransactionExecutionCallback;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.dangdang.ddframe.job.util.concurrent.BlockUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 作业分片服务.
 * 
 * @author zhangliang
 */
@Slf4j
public final class ShardingService {
    
    private final String jobName;
    
    private final JobNodeStorage jobNodeStorage;
    
    private final LeaderService leaderService;
    
    private final ConfigurationService configService;
    
    private final InstanceService instanceService;
    
    private final ServerService serverService;
    
    private final ExecutionService executionService;

    private final JobNodePath jobNodePath;
    
    public ShardingService(final CoordinatorRegistryCenter regCenter, final String jobName) {
        this.jobName = jobName;
        jobNodeStorage = new JobNodeStorage(regCenter, jobName);
        leaderService = new LeaderService(regCenter, jobName);
        configService = new ConfigurationService(regCenter, jobName);
        instanceService = new InstanceService(regCenter, jobName);
        serverService = new ServerService(regCenter, jobName);
        executionService = new ExecutionService(regCenter, jobName);
        jobNodePath = new JobNodePath(jobName);
    }
    
    /**
     * 设置需要重新分片的标记.
     */
    public void setReshardingFlag() {
        jobNodeStorage.createJobNodeIfNeeded(ShardingNode.NECESSARY);
    }
    
    /**
     * 判断是否需要重分片.
     * 
     * @return 是否需要重分片
     */
    public boolean isNeedSharding() {
        return jobNodeStorage.isJobNodeExisted(ShardingNode.NECESSARY);
    }
    
    /**
     * 如果需要分片且当前节点为主节点, 则作业分片.
     * 
     * <p>
     * 如果当前无可用节点则不分片.
     * </p>
     */
    public void shardingIfNecessary() {
        List<JobInstance> availableJobInstances = instanceService.getAvailableJobInstances();
        if (!isNeedSharding() // 判断是否需要重新分片
                || availableJobInstances.isEmpty()) {
            return;
        }
        // 【非主节点】等待 作业分片项分配完成
        if (!leaderService.isLeaderUntilBlock()) { // 判断是否为【主节点】
            blockUntilShardingCompleted();
            return;
        }
        // 【主节点】作业分片项分配
        // 等待 作业未在运行中状态
        waitingOtherJobCompleted();
        // 加载liteJob的配置
        LiteJobConfiguration liteJobConfig = configService.load(false);
        // 分片数量
        int shardingTotalCount = liteJobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount();
        // 设置 作业正在重分片的标记
        log.debug("Job '{}' sharding begin.", jobName);
        jobNodeStorage.fillEphemeralJobNode(ShardingNode.PROCESSING, "");
        // 重置 作业分片项信息
        resetShardingInfo(shardingTotalCount);
        // 【事务中】设置 作业分片项信息
        JobShardingStrategy jobShardingStrategy = JobShardingStrategyFactory.getStrategy(liteJobConfig.getJobShardingStrategyClass());
        jobNodeStorage.executeInTransaction(new PersistShardingInfoTransactionExecutionCallback(jobShardingStrategy.sharding(availableJobInstances, jobName, shardingTotalCount)));
        log.debug("Job '{}' sharding complete.", jobName);
    }
    
    private void blockUntilShardingCompleted() {
        while (!leaderService.isLeaderUntilBlock() // 当前作业节点不为【主节点】
                && (jobNodeStorage.isJobNodeExisted(ShardingNode.NECESSARY) // 存在作业需要重分片的标记
                    || jobNodeStorage.isJobNodeExisted(ShardingNode.PROCESSING))) { // 存在作业正在重分片的标记
            log.debug("Job '{}' sleep short time until sharding completed.", jobName);
            BlockUtils.waitingShortTime();
        }
    }
    
    private void waitingOtherJobCompleted() {
        while (executionService.hasRunningItems()) {
            log.debug("Job '{}' sleep short time until other job completed.", jobName);
            BlockUtils.waitingShortTime();
        }
    }
    
    private void resetShardingInfo(final int shardingTotalCount) {
        // 重置 有效的作业分片项
        for (int i = 0; i < shardingTotalCount; i++) {
            jobNodeStorage.removeJobNodeIfExisted(ShardingNode.getInstanceNode(i)); // 移除 `/${JOB_NAME}/sharding/${ITEM_ID}/instance`
            jobNodeStorage.createJobNodeIfNeeded(ShardingNode.ROOT + "/" + i); // 创建 `/${JOB_NAME}/sharding/${ITEM_ID}`
        }
        // 移除 多余的作业分片项
        int actualShardingTotalCount = jobNodeStorage.getJobNodeChildrenKeys(ShardingNode.ROOT).size();
        if (actualShardingTotalCount > shardingTotalCount) {
            for (int i = shardingTotalCount; i < actualShardingTotalCount; i++) {
                jobNodeStorage.removeJobNodeIfExisted(ShardingNode.ROOT + "/" + i); // 移除 `/${JOB_NAME}/sharding/${ITEM_ID}`
            }
        }
    }
    
    /**
     * 获取作业运行实例的分片项集合.
     *
     * @param jobInstanceId 作业运行实例主键
     * @return 作业运行实例的分片项集合
     */
    public List<Integer> getShardingItems(final String jobInstanceId) {
        JobInstance jobInstance = new JobInstance(jobInstanceId);
        if (!serverService.isAvailableServer(jobInstance.getIp())) {
            return Collections.emptyList();
        }
        List<Integer> result = new LinkedList<>();
        int shardingTotalCount = configService.load(true).getTypeConfig().getCoreConfig().getShardingTotalCount();
        for (int i = 0; i < shardingTotalCount; i++) {
            if (jobInstance.getJobInstanceId().equals(jobNodeStorage.getJobNodeData(ShardingNode.getInstanceNode(i)))) { //
                result.add(i);
            }
        }
        return result;
    }
    
    /**
     * 获取运行在本作业实例的分片项集合.
     * 
     * @return 运行在本作业实例的分片项集合
     */
    public List<Integer> getLocalShardingItems() {
        if (JobRegistry.getInstance().isShutdown(jobName) || !serverService.isAvailableServer(JobRegistry.getInstance().getJobInstance(jobName).getIp())) {
            return Collections.emptyList();
        }
        return getShardingItems(JobRegistry.getInstance().getJobInstance(jobName).getJobInstanceId());
    }
    
    /**
     * 查询是包含有分片节点的不在线服务器.
     * 
     * @return 是包含有分片节点的不在线服务器
     */
    public boolean hasShardingInfoInOfflineServers() {
        List<String> onlineInstances = jobNodeStorage.getJobNodeChildrenKeys(InstanceNode.ROOT); // `/${JOB_NAME}/instances/${JOB_INSTANCE_ID}`
        int shardingTotalCount = configService.load(true).getTypeConfig().getCoreConfig().getShardingTotalCount();
        for (int i = 0; i < shardingTotalCount; i++) {
            if (!onlineInstances.contains(jobNodeStorage.getJobNodeData(ShardingNode.getInstanceNode(i)))) { // `/${JOB_NAME}/sharding/${ITEM_INDEX}/instance`
                return true;
            }
        }
        return false;
    }
    
    @RequiredArgsConstructor
    class PersistShardingInfoTransactionExecutionCallback implements TransactionExecutionCallback {

        /**
         * 作业分片项分配结果
         * key：作业节点
         * value：作业分片项
         */
        private final Map<JobInstance, List<Integer>> shardingResults;
        
        @Override
        public void execute(final CuratorTransactionFinal curatorTransactionFinal) throws Exception {
            // 设置 每个节点分配的作业分片项
            for (Map.Entry<JobInstance, List<Integer>> entry : shardingResults.entrySet()) {
                for (int shardingItem : entry.getValue()) {
                    curatorTransactionFinal.create().forPath(jobNodePath.getFullPath(ShardingNode.getInstanceNode(shardingItem))
                            , entry.getKey().getJobInstanceId().getBytes()).and();
                }
            }
            // 移除 作业需要重分片的标记、作业正在重分片的标记
            curatorTransactionFinal.delete().forPath(jobNodePath.getFullPath(ShardingNode.NECESSARY)).and();
            curatorTransactionFinal.delete().forPath(jobNodePath.getFullPath(ShardingNode.PROCESSING)).and();
        }
    }
}
