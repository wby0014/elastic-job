# Elastic-Job - distributed scheduled job solution

# 个人博客

[http://www.iocoder.cn](http://www.iocoder.cn/?github)

-------

![](http://www.iocoder.cn/images/common/wechat_mp.jpeg)

> 🙂🙂🙂关注**微信公众号：【芋艿的后端小屋】**有福利：  
> 1. RocketMQ / MyCAT / Sharding-JDBC **所有**源码分析文章列表  
> 2. RocketMQ / MyCAT / Sharding-JDBC **中文注释源码 GitHub 地址**  
> 3. 您对于源码的疑问每条留言**都**将得到**认真**回复。**甚至不知道如何读源码也可以请教噢**。  
> 4. **新的**源码解析文章**实时**收到通知。**每周更新一篇左右**。

-------

* 知识星球：![知识星球](http://www.iocoder.cn/images/Architecture/2017_12_29/01.png)

* 调度作业中间件 **Elastic-Job-Lite**
    * [《Elastic-Job 源码分析 —— 为什么阅读 Elastic-Job 源码？》](http://www.iocoder.cn/Elastic-Job/why-read-Elastic-Job-source-code?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业配置》](http://www.iocoder.cn/Elastic-Job/job-config?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业初始化》](http://www.iocoder.cn/Elastic-Job/job-init?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业执行》](http://www.iocoder.cn/Elastic-Job/job-execute?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 注册中心》](http://www.iocoder.cn/Elastic-Job/reg-center-zookeeper?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业数据存储》](http://www.iocoder.cn/Elastic-Job/job-storage?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 注册中心监听器》](http://www.iocoder.cn/Elastic-Job/reg-center-zookeeper-listener?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 主节点选举》](http://www.iocoder.cn/Elastic-Job/election?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业分片策略》](http://www.iocoder.cn/Elastic-Job/job-sharding-strategy?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业分片》](http://www.iocoder.cn/Elastic-Job/job-sharding?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业失效转移》](http://www.iocoder.cn/Elastic-Job/job-failover?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业事件追踪》](http://www.iocoder.cn/Elastic-Job/job-event-trace?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业监听器》](http://www.iocoder.cn/Elastic-Job/job-listener?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 自诊断修复》](http://www.iocoder.cn/Elastic-Job/reconcile?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 作业监控服务》](http://www.iocoder.cn/Elastic-Job/job-monitor?github&1604)
    * [《Elastic-Job-Lite 源码分析 —— 运维平台》](http://www.iocoder.cn/Elastic-Job/job-console?github&1604)

* 调度作业中间件 **Elastic-Job-Cloud**
    * [《Elastic-Job-Cloud 源码分析 —— 作业配置》](http://www.iocoder.cn/Elastic-Job/cloud-job-config?github&1605)
    * [《Elastic-Job-Cloud 源码分析 —— 作业调度（一）》](http://www.iocoder.cn/Elastic-Job/cloud-job-scheduler-and-executor-first?github&1605)
    * [《Elastic-Job-Cloud 源码分析 —— 作业调度（二）》](http://www.iocoder.cn/Elastic-Job/cloud-job-scheduler-and-executor-second?github&1605)
    * [《Elastic-Job-Cloud 源码分析 —— 本地运行模式》](http://www.iocoder.cn/Elastic-Job/cloud-local-executor?github&1605)
    * [《Elastic-Job-Cloud 源码分析 —— 作业失效转移》](http://www.iocoder.cn/Elastic-Job/cloud-job-failover?github&1605)
    * [《Elastic-Job-Cloud 源码分析 —— 高可用》](http://www.iocoder.cn/Elastic-Job/cloud-high-availability?github&1605)

[![Build Status](https://secure.travis-ci.org/dangdangdotcom/elastic-job.png?branch=master)](https://travis-ci.org/dangdangdotcom/elastic-job)
[![Maven Status](https://maven-badges.herokuapp.com/maven-central/com.dangdang/elastic-job/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.dangdang/elastic-job)
[![Coverage Status](https://coveralls.io/repos/dangdangdotcom/elastic-job/badge.svg?branch=master&service=github)](https://coveralls.io/github/dangdangdotcom/elastic-job?branch=master)
[![GitHub release](https://img.shields.io/github/release/dangdangdotcom/elastic-job.svg)](https://github.com/dangdangdotcom/elastic-job/releases)
[![Hex.pm](http://dangdangdotcom.github.io/elastic-job/elastic-job-lite/img/license.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)

# [Elastic-Job-Lite中文主页](http://dangdangdotcom.github.io/elastic-job/elastic-job-lite) [![GitHub release](https://img.shields.io/badge/release-download-orange.svg)](https://dangdangdotcom.github.io/elastic-job/elastic-job-lite/dist/elastic-job-lite-console-2.1.4.tar.gz)
# [Elastic-Job-Cloud中文主页](http://dangdangdotcom.github.io/elastic-job/elastic-job-cloud) [![GitHub release](https://img.shields.io/badge/release-download-orange.svg)](https://dangdangdotcom.github.io/elastic-job/elastic-job-cloud/dist/elastic-job-cloud-scheduler-2.1.4.tar.gz)
# [Elastic-Job 1.x中文主页(已废弃)](http://dangdangdotcom.github.io/elastic-job/elastic-job-lite-1.x)

# Overview

Elastic-Job is a distributed scheduled job solution. Elastic-Job is composited from 2 independent sub projects: Elastic-Job-Lite and Elastic-Job-Cloud.

Elastic-Job-Lite is a centre-less solution, use lightweight jar to coordinate distributed jobs.
Elastic-Job-Cloud is a Mesos framework which use Mesos + Docker(todo) to manage and isolate resources and processes.

Elastic-Job-Lite and Elastic-Job-Cloud provide unified API. Developers only need code one time, then decide to deploy Lite or Cloud as you want.

# Features

## 1. Elastic-Job-Lite

* Distributed schedule job coordinate
* Elastic scale in and scale out supported
* Failover
* Misfired jobs refire
* Sharding consistently, same sharding item for a job only one running instance
* Self diagnose and recover when distribute environment unstable
* Parallel scheduling supported
* Job lifecycle operation
* Lavish job types
* Spring integrated and namespace supported
* Web console

## 2. Elastic-Job-Cloud
* All Elastic-Job-Lite features included
* Application distributed automatically
* Fenzo based resources allocated elastically
* Docker based processes isolation support (TBD)

# Architecture

## Elastic-Job-Lite

![Elastic-Job-Lite Architecture](http://dangdangdotcom.github.io/elastic-job/elastic-job-lite/img/architecture/elastic_job_lite.png)
***

## Elastic-Job-Cloud

![Elastic-Job-Cloud Architecture](http://dangdangdotcom.github.io/elastic-job/elastic-job-cloud/img/architecture/elastic_job_cloud.png)


# [Release Notes](https://github.com/dangdangdotcom/elastic-job/releases)

# [Roadmap](ROADMAP.md)

# Quick Start

## Elastic-Job-Lite

### Add maven dependency

```xml
<!-- import elastic-job lite core -->
<dependency>
    <groupId>com.dangdang</groupId>
    <artifactId>elastic-job-lite-core</artifactId>
    <version>${lasted.release.version}</version>
</dependency>

<!-- import other module if need -->
<dependency>
    <groupId>com.dangdang</groupId>
    <artifactId>elastic-job-lite-spring</artifactId>
    <version>${lasted.release.version}</version>
</dependency>
```
### Job development

```java
public class MyElasticJob implements SimpleJob {
    
    @Override
    public void execute(ShardingContext context) {
        switch (context.getShardingItem()) {
            case 0: 
                // do something by sharding item 0
                break;
            case 1: 
                // do something by sharding item 1
                break;
            case 2: 
                // do something by sharding item 2
                break;
            // case n: ...
        }
    }
}
```

### Job configuration

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:reg="http://www.dangdang.com/schema/ddframe/reg"
    xmlns:job="http://www.dangdang.com/schema/ddframe/job"
    xsi:schemaLocation="http://www.springframework.org/schema/beans
                        http://www.springframework.org/schema/beans/spring-beans.xsd
                        http://www.dangdang.com/schema/ddframe/reg
                        http://www.dangdang.com/schema/ddframe/reg/reg.xsd
                        http://www.dangdang.com/schema/ddframe/job
                        http://www.dangdang.com/schema/ddframe/job/job.xsd
                        ">
    <!--configure registry center -->
    <reg:zookeeper id="regCenter" server-lists="yourhost:2181" namespace="dd-job" base-sleep-time-milliseconds="1000" max-sleep-time-milliseconds="3000" max-retries="3" />

    <!--configure job -->
    <job:simple id="myElasticJob" class="xxx.MyElasticJob" registry-center-ref="regCenter" cron="0/10 * * * * ?"   sharding-total-count="3" sharding-item-parameters="0=A,1=B,2=C" />
</beans>
```

***

## Elastic-Job-Cloud

### Add maven dependency

```xml
<!-- import elastic-job cloud executor -->
<dependency>
    <groupId>com.dangdang</groupId>
    <artifactId>elastic-job-cloud-executor</artifactId>
    <version>${lasted.release.version}</version>
</dependency>
```

### Job development

Same with `Elastic-Job-Lite`

### Job App configuration

```shell
curl -l -H "Content-type: application/json" -X POST -d '{"appName":"yourAppName","appURL":"http://app_host:8080/foo-job.tar.gz","cpuCount":0.1,"memoryMB":64.0,"bootstrapScript":"bin/start.sh","appCacheEnable":true}' http://elastic_job_cloud_host:8899/api/app
```

### Job configuration

```shell
curl -l -H "Content-type: application/json" -X POST -d '{"jobName":"foo_job","appName":"yourAppName","jobClass":"yourJobClass","jobType":"SIMPLE","jobExecutionType":"TRANSIENT","cron":"0/5 * * * * ?","shardingTotalCount":5,"cpuCount":0.1,"memoryMB":64.0,"failover":true,"misfire":true,"bootstrapScript":"bin/start.sh"}' http://elastic_job_cloud_host:8899/api/job/register
```
