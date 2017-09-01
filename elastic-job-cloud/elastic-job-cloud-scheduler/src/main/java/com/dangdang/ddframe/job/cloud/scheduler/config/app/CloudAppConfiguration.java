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

package com.dangdang.ddframe.job.cloud.scheduler.config.app;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 云作业App配置对象.
 *
 * @author caohao
 */
@AllArgsConstructor
@RequiredArgsConstructor
@Getter
@ToString
public final class CloudAppConfiguration {

    /**
     * 应用名
     */
    private final String appName;
    /**
     * 应用包地址
     */
    private final String appURL;
    /**
     * 应用启动脚本
     */
    private final String bootstrapScript;
    /**
     * cpu 数量
     * // TODO 芋艿：多jvm，怎么限制资源
     */
    private double cpuCount = 1;
    /**
     * 内存 大小
     */
    private double memoryMB = 128;
    /**
     * 每次执行作业时是否从缓存中读取应用。禁用则每次执行任务均从应用仓库下载应用至本地
     * TODO 芋艿：看看实现
     */
    private boolean appCacheEnable = true;
    /**
     * 常驻作业事件采样率统计条数，默认不采样全部记录。
     * 为避免数据量过大，可对频繁调度的常驻作业配置采样率，即作业每执行N次，才会记录作业执行及追踪相关数据
     * TODO 芋艿：看看实现
     */
    private int eventTraceSamplingCount;
}
