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

package com.dangdang.ddframe.job.cloud.scheduler.config.job;

import com.dangdang.ddframe.job.config.JobRootConfiguration;
import com.dangdang.ddframe.job.config.JobTypeConfiguration;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 云作业配置对象.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
@AllArgsConstructor
@Getter
public final class CloudJobConfiguration implements JobRootConfiguration {

    /**
     * 作业应用名称 {@link com.dangdang.ddframe.job.cloud.scheduler.config.app.CloudAppConfiguration}
     */
    private final String appName;
    /**
     * 作业类型配置
     */
    private final JobTypeConfiguration typeConfig;
    /**
     * 单片作业所需要的CPU数量，最小值为0.001
     */
    private final double cpuCount;
    /**
     * 单片作业所需要的内存MB，最小值为1
     */
    private final double memoryMB;
    /**
     * 作业执行类型
     */
    private final CloudJobExecutionType jobExecutionType;
    /**
     * Spring容器中配置的bean名称
     */
    private String beanName;
    /**
     * Spring方式配置Spring配置文件相对路径以及名称，如：META-INF\applicationContext.xml
     */
    private String applicationContext; 
    
    /**
     * 获取作业名称.
     *
     * @return 作业名称
     */
    public String getJobName() {
        return typeConfig.getCoreConfig().getJobName();
    }
}
