package com.dangdang.ddframe.job.lite.internal.listener;

import com.dangdang.ddframe.job.lite.internal.sharding.ExecutionService;
import com.dangdang.ddframe.job.lite.internal.instance.InstanceService;
import com.dangdang.ddframe.job.lite.internal.schedule.JobRegistry;
import com.dangdang.ddframe.job.lite.internal.schedule.JobScheduleController;
import com.dangdang.ddframe.job.lite.internal.server.ServerService;
import com.dangdang.ddframe.job.lite.internal.sharding.ShardingService;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;

/**
 * 注册中心连接状态监听器.
 *
 * @author zhangliang
 */
public final class RegistryCenterConnectionStateListener implements ConnectionStateListener {
    
    private final String jobName;
    
    private final ServerService serverService;
    
    private final InstanceService instanceService;
    
    private final ShardingService shardingService;
    
    private final ExecutionService executionService;
    
    public RegistryCenterConnectionStateListener(final CoordinatorRegistryCenter regCenter, final String jobName) {
        this.jobName = jobName;
        serverService = new ServerService(regCenter, jobName);
        instanceService = new InstanceService(regCenter, jobName);
        shardingService = new ShardingService(regCenter, jobName);
        executionService = new ExecutionService(regCenter, jobName);
    }
    
    @Override
    public void stateChanged(final CuratorFramework client, final ConnectionState newState) {
        if (JobRegistry.getInstance().isShutdown(jobName)) {
            return;
        }
        JobScheduleController jobScheduleController = JobRegistry.getInstance().getJobScheduleController(jobName);
        if (ConnectionState.SUSPENDED == newState || ConnectionState.LOST == newState) { // Zookeeper 连接终端 或 连接丢失
            // 暂时作业调度
            jobScheduleController.pauseJob();
        } else if (ConnectionState.RECONNECTED == newState) { // Zookeeper 重新连上
            // 持久化作业服务器上线信息
            serverService.persistOnline(serverService.isEnableServer(JobRegistry.getInstance().getJobInstance(jobName).getIp()));
            // 持久化作业运行实例上线相关信息
            instanceService.persistOnline();
            // 清除本地分配的作业分片项运行中的标记
            executionService.clearRunningInfo(shardingService.getLocalShardingItems());
            // 恢复作业调度
            jobScheduleController.resumeJob();
        }
    }
}
