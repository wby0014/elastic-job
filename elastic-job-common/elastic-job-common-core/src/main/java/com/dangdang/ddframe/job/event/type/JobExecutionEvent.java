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

package com.dangdang.ddframe.job.event.type;

import com.dangdang.ddframe.job.event.JobEvent;
import com.dangdang.ddframe.job.exception.ExceptionUtil;
import com.dangdang.ddframe.job.util.env.IpUtils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.UUID;

/**
 * 作业执行事件.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
@AllArgsConstructor
@Getter
public final class JobExecutionEvent implements JobEvent {

    /**
     * 主键
     */
    private String id = UUID.randomUUID().toString();
    /**
     * 主机名称
     */
    private String hostname = IpUtils.getHostName();
    /**
     * IP
     */
    private String ip = IpUtils.getIp();
    /**
     * 作业任务ID
     */
    private final String taskId;
    /**
     * 作业名字
     */
    private final String jobName;
    /**
     * 执行来源
     */
    private final ExecutionSource source;
    /**
     * 作业分片项
     */
    private final int shardingItem;
    /**
     * 开始时间
     */
    private Date startTime = new Date();
    /**
     * 结束时间
     */
    @Setter
    private Date completeTime;
    /**
     * 是否执行成功
     */
    @Setter
    private boolean success;
    /**
     * 执行失败原因
     */
    @Setter
    private JobExecutionEventThrowable failureCause;
    
    /**
     * 作业执行成功.
     * 
     * @return 作业执行事件
     */
    public JobExecutionEvent executionSuccess() {
        JobExecutionEvent result = new JobExecutionEvent(id, hostname, ip, taskId, jobName, source, shardingItem, startTime, completeTime, success, failureCause);
        result.setCompleteTime(new Date());
        result.setSuccess(true);
        return result;
    }
    
    /**
     * 作业执行失败.
     * 
     * @param failureCause 失败原因
     * @return 作业执行事件
     */
    public JobExecutionEvent executionFailure(final Throwable failureCause) {
        JobExecutionEvent result = new JobExecutionEvent(id, hostname, ip, taskId, jobName, source, shardingItem, startTime, completeTime, success, new JobExecutionEventThrowable(failureCause));
        result.setCompleteTime(new Date());
        result.setSuccess(false);
        return result;
    }
    
    /**
     * 获取失败原因.
     * 
     * @return 失败原因
     */
    public String getFailureCause() {
        return ExceptionUtil.transform(failureCause == null ? null : failureCause.getThrowable());
    }
    
    /**
     * 执行来源.
     */
    public enum ExecutionSource {
        /**
         * 普通触发执行
         */
        NORMAL_TRIGGER,
        /**
         * 被错过执行
         */
        MISFIRE,
        /**
         * 失效转移执行
         */
        FAILOVER
    }
}
