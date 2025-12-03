# Elastic-Job 整体架构与流程分析

## 一、项目启动流程（基于 SpringMain.java）

### 1.1 启动入口

```29:31:elastic-job-example/elastic-job-example-lite-spring/src/main/java/com/dangdang/ddframe/job/example/SpringMain.java
        EmbedZookeeperServer.start(EMBED_ZOOKEEPER_PORT);
        new ClassPathXmlApplicationContext("classpath:META-INF/applicationContext.xml");
```

**启动步骤：**
1. **启动嵌入式 Zookeeper**：使用 Curator 的 `TestingServer` 启动一个内存版 Zookeeper（端口 5181），仅用于示例运行
2. **加载 Spring 配置**：加载 `applicationContext.xml`，触发 Spring Bean 的初始化和依赖注入

### 1.2 Spring 配置解析

```26:35:elastic-job-example/elastic-job-example-lite-spring/src/main/resources/META-INF/applicationContext.xml
    <reg:zookeeper id="regCenter" server-lists="${serverLists}" namespace="${namespace}"
                   base-sleep-time-milliseconds="${baseSleepTimeMilliseconds}"
                   max-sleep-time-milliseconds="${maxSleepTimeMilliseconds}" max-retries="${maxRetries}"/>

    <job:simple id="${simple.id}" class="${simple.class}" registry-center-ref="regCenter"
                sharding-total-count="${simple.shardingTotalCount}" cron="${simple.cron}"
                sharding-item-parameters="${simple.shardingItemParameters}"
                monitor-execution="${simple.monitorExecution}" monitor-port="${simple.monitorPort}"
                failover="${simple.failover}" description="${simple.description}" disabled="${simple.disabled}"
                overwrite="${simple.overwrite}" event-trace-rdb-data-source="elasticJobLog"/>
```

**配置内容：**
- **注册中心配置** (`reg:zookeeper`)：配置 Zookeeper 连接信息
- **作业配置** (`job:simple`)：配置具体的作业，包括：
  - 作业类名
  - Cron 表达式
  - 分片总数
  - 分片参数
  - 监控、失效转移等特性

---

## 二、核心架构组件

### 2.1 模块划分

Elastic-Job 采用模块化设计，主要模块包括：

1. **elastic-job-common**：公共模块
   - `elastic-job-common-core`：核心接口和实现
   - `elastic-job-common-restful`：RESTful API 支持

2. **elastic-job-lite**：轻量级版本（本文重点）
   - `elastic-job-lite-core`：核心调度逻辑
   - `elastic-job-lite-spring`：Spring 集成
   - `elastic-job-lite-lifecycle`：生命周期管理
   - `elastic-job-lite-console`：控制台

3. **elastic-job-cloud**：云版本（基于 Mesos）

4. **elastic-job-example**：示例代码

### 2.2 核心类关系图

```
SpringMain
    ↓
ClassPathXmlApplicationContext
    ↓
SpringJobScheduler (extends JobScheduler)
    ↓
JobScheduler.init()
    ├── ZookeeperRegistryCenter (注册中心)
    ├── SchedulerFacade (调度器门面)
    ├── JobFacade (作业门面)
    ├── JobScheduleController (Quartz 调度控制器)
    └── LiteJob (Quartz Job 实现)
        ↓
    JobExecutorFactory.getJobExecutor()
        ↓
    AbstractElasticJobExecutor.execute()
        ↓
    SimpleJobExecutor / DataflowJobExecutor / ScriptJobExecutor
        ↓
    用户实现的 Job.execute(ShardingContext)
```

---

## 三、详细执行流程

### 3.1 初始化阶段（JobScheduler.init()）

```120:134:elastic-job-lite/elastic-job-lite-core/src/main/java/com/dangdang/ddframe/job/lite/api/JobScheduler.java
    public void init() {
        // 更新 作业配置
        LiteJobConfiguration liteJobConfigFromRegCenter = schedulerFacade.updateJobConfiguration(liteJobConfig);
        // 设置 当前作业分片总数
        JobRegistry.getInstance().setCurrentShardingTotalCount(liteJobConfigFromRegCenter.getJobName(), liteJobConfigFromRegCenter.getTypeConfig().getCoreConfig().getShardingTotalCount());
        // 创建 作业调度控制器， createJobDetail方法里创建了LiteJob，这个类很重要，Job触发时会调用该类的execute方法
        JobScheduleController jobScheduleController = new JobScheduleController(
                createScheduler(), createJobDetail(liteJobConfigFromRegCenter.getTypeConfig().getJobClass()), liteJobConfigFromRegCenter.getJobName());
        // 添加 作业调度控制器
        JobRegistry.getInstance().registerJob(liteJobConfigFromRegCenter.getJobName(), jobScheduleController, regCenter);
        // 注册 作业启动信息
        schedulerFacade.registerStartUpInfo(!liteJobConfigFromRegCenter.isDisabled());
        // 调度作业
        jobScheduleController.scheduleJob(liteJobConfigFromRegCenter.getTypeConfig().getCoreConfig().getCron());
    }
```

**初始化步骤详解：**

1. **更新作业配置**：从注册中心（Zookeeper）读取最新配置，如果配置不存在则写入
2. **设置分片总数**：在 `JobRegistry` 中记录作业的分片总数
3. **创建 Quartz Scheduler**：创建 Quartz 调度器实例
4. **创建 JobDetail**：创建 Quartz JobDetail，包含：
   - `LiteJob` 类（Quartz Job 实现）
   - `ElasticJob` 实例（用户实现的作业）
   - `JobFacade`（作业门面，提供各种服务）
5. **注册到 JobRegistry**：将作业调度控制器注册到全局注册表
6. **注册启动信息**：调用 `schedulerFacade.registerStartUpInfo()`，执行：
   - 启动所有监听器
   - 选举主节点
   - 持久化服务器上线信息
   - 持久化运行实例信息
   - 设置重新分片标记
   - 启动监控服务
   - 启动调解服务
7. **调度作业**：使用 Cron 表达式启动 Quartz 调度

### 3.2 注册启动信息（registerStartUpInfo）

```135:152:elastic-job-lite/elastic-job-lite-core/src/main/java/com/dangdang/ddframe/job/lite/internal/schedule/SchedulerFacade.java
    public void registerStartUpInfo(final boolean enabled) {
        // 开启 所有监听器
        listenerManager.startAllListeners();
        // 选举 主节点
        leaderService.electLeader();
        // 持久化 作业服务器上线信息
        serverService.persistOnline(enabled);
        // 持久化 作业运行实例上线相关信息
        instanceService.persistOnline();
        // 设置 需要重新分片的标记
        shardingService.setReshardingFlag();
        // 初始化 作业监听服务
        monitorService.listen();
        // 初始化 调解作业不一致状态服务
        if (!reconcileService.isRunning()) {
            reconcileService.startAsync();
        }
    }
```

**关键操作：**
- **选举主节点**：使用 Zookeeper 的临时节点实现主节点选举，主节点负责分片和失效转移
- **持久化上线信息**：在 Zookeeper 中创建临时节点，表示服务器和实例在线
- **设置重新分片标记**：标记需要重新分片，触发分片逻辑

### 3.3 作业执行阶段（Quartz 触发）

当 Quartz 根据 Cron 表达式触发作业时：

```32:35:elastic-job-lite/elastic-job-lite-core/src/main/java/com/dangdang/ddframe/job/lite/internal/schedule/LiteJob.java
    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        JobExecutorFactory.getJobExecutor(elasticJob, jobFacade).execute();
    }
```

`LiteJob.execute()` 是 Quartz 调用的入口，它通过 `JobExecutorFactory` 获取对应的执行器并执行。

### 3.4 作业执行器执行（AbstractElasticJobExecutor.execute()）

```128:180:elastic-job-common/elastic-job-common-core/src/main/java/com/dangdang/ddframe/job/executor/AbstractElasticJobExecutor.java
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
        // 什么是任务错过机制，一个任务执行太长，到达下一个触发时间点时 还未执行完，那么就是任务错过机制。
        // 任务错过会写入 zk节点信息。 当任务执行完成后 ，发现有错过节点信息，那么获取 错过的节点信息 再执行一次
```

**执行步骤：**

1. **检查执行环境**：验证作业是否可以执行
2. **获取分片上下文**：调用 `jobFacade.getShardingContexts()`，这是**核心步骤**：
   - 如果需要失效转移，获取失效转移的分片项
   - 如果需要分片，主节点执行分片逻辑
   - 获取分配给当前实例的分片项
3. **处理错过任务**：如果上次任务还在运行，跳过本次执行
4. **执行作业前回调**：调用监听器的 `beforeJobExecuted` 方法
5. **执行作业**：调用 `execute()` 方法执行具体的分片

### 3.5 分片逻辑（getShardingContexts）

```128:144:elastic-job-lite/elastic-job-lite-core/src/main/java/com/dangdang/ddframe/job/lite/internal/schedule/LiteJobFacade.java
    public ShardingContexts getShardingContexts() {
        // 获得 失效转移的作业分片项
        boolean isFailover = configService.load(true).isFailover();
        if (isFailover) {
            // 如果开启了失效转移，获取需要本作业服务器的执行的 失效转移分片项集合
            // 由哪些分片执行这些失效的作业，也是由主节点选的
            List<Integer> failoverShardingItems = failoverService.getLocalFailoverItems();
            if (!failoverShardingItems.isEmpty()) {
                return executionContextService.getJobShardingContext(failoverShardingItems);
            }
        }
        // 作业分片，如果需要分片且当前节点为主节点，
        // wuby 主节点执行数据分片主要逻辑，如果当前需要分片则选举一个作业主节点，作业主节点来执行分片的逻辑，将分片项按分片算法拆分给当前在线的进程实例
        shardingService.shardingIfNecessary();
        // 获得 分配在本机的作业分片项, 例如分配给本机 0，1分片项， 移除无效分片
        List<Integer> shardingItems = shardingService.getLocalShardingItems();
```

**分片流程：**

1. **失效转移检查**：如果开启失效转移且有失效的分片，优先执行失效转移的分片
2. **执行分片**：`shardingService.shardingIfNecessary()`：
   - 只有主节点执行分片逻辑
   - 获取所有在线的作业实例
   - 使用分片算法（默认是平均分配）将分片项分配给各个实例
   - 将分片结果写入 Zookeeper
3. **获取本地分片**：从 Zookeeper 读取分配给当前实例的分片项

### 3.6 执行分片（process 方法）

```188:221:elastic-job-common/elastic-job-common-core/src/main/java/com/dangdang/ddframe/job/executor/AbstractElasticJobExecutor.java
    private void execute(final ShardingContexts shardingContexts, final JobExecutionEvent.ExecutionSource executionSource) {
        // 无可执行的分片，发布作业状态追踪事件(State.TASK_FINISHED)
        if (shardingContexts.getShardingItemParameters().isEmpty()) {
            if (shardingContexts.isAllowSendJobEvent()) {
                jobFacade.postJobStatusTraceEvent(shardingContexts.getTaskId(), State.TASK_FINISHED, String.format("Sharding item for job '%s' is empty.", jobName));
            }
            return;
        }
        // 注册作业启动信息，即创建类似sharding/0/running/jobName的临时节点
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
```

**执行流程：**

1. **检查分片**：如果没有分片项，直接返回
2. **注册作业开始**：在 Zookeeper 中创建运行节点（如 `/sharding/0/running/jobName`）
3. **执行分片**：调用 `process()` 方法，遍历所有分片项，使用线程池并发执行
4. **注册作业完成**：删除运行节点，标记作业完成

### 3.7 执行单个分片（process 方法）

```274:301:elastic-job-common/elastic-job-common-core/src/main/java/com/dangdang/ddframe/job/executor/AbstractElasticJobExecutor.java
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
```

最终调用用户实现的 `Job.execute(ShardingContext)` 方法。

---

## 四、Zookeeper 数据结构

Elastic-Job 使用 Zookeeper 存储作业配置和运行状态，主要路径结构：

```
/{namespace}/
    ├── {jobName}/
    │   ├── config/              # 作业配置
    │   ├── servers/              # 服务器信息
    │   │   └── {ip}/
    │   ├── instances/            # 运行实例
    │   │   └── {jobInstanceId}/
    │   ├── sharding/             # 分片信息
    │   │   ├── {item}/           # 分片项
    │   │   │   ├── instance      # 分配的实例
    │   │   │   └── running/      # 运行中的分片
    │   │   └── {item}/failover/  # 失效转移
    │   ├── leader/               # 主节点选举
    │   │   ├── election/
    │   │   └── sharding/
    │   └── execution/           # 执行信息
```

---

## 五、关键特性

### 5.1 分片机制

- **分片算法**：默认使用平均分配算法，将分片项均匀分配给各个实例
- **分片时机**：
  - 作业启动时
  - 实例上线/下线时（通过 Zookeeper 监听器触发）
  - 手动触发重新分片

### 5.2 失效转移（Failover）

- **触发条件**：作业执行失败时
- **转移流程**：
  1. 执行失败的分片项写入 `/sharding/{item}/failover` 节点
  2. 主节点检测到失效转移节点
  3. 将失效的分片项分配给其他可用实例
  4. 新实例执行失效的分片项

### 5.3 主节点选举

- **实现方式**：使用 Zookeeper 的临时节点实现
- **主节点职责**：
  - 执行分片逻辑
  - 处理失效转移
  - 监控作业状态

### 5.4 错过任务（Misfire）

- **触发条件**：上次任务还在执行，新的触发时间已到
- **处理方式**：
  - 跳过本次执行
  - 记录错过标记
  - 任务完成后执行错过的任务

---

## 六、阅读源码建议

### 6.1 核心类阅读顺序

1. **入口类**：
   - `SpringMain` / `JavaMain`：了解如何启动
   - `SpringJobScheduler`：了解如何集成 Spring

2. **调度核心**：
   - `JobScheduler`：调度器主类
   - `JobScheduleController`：Quartz 调度控制器
   - `LiteJob`：Quartz Job 实现

3. **执行核心**：
   - `AbstractElasticJobExecutor`：执行器基类
   - `SimpleJobExecutor` / `DataflowJobExecutor`：具体执行器
   - `LiteJobFacade`：作业门面

4. **分片与协调**：
   - `ShardingService`：分片服务
   - `LeaderService`：主节点服务
   - `FailoverService`：失效转移服务
   - `SchedulerFacade`：调度器门面

5. **注册中心**：
   - `ZookeeperRegistryCenter`：Zookeeper 注册中心实现
   - `CoordinatorRegistryCenter`：注册中心接口

### 6.2 调试建议

1. **启动示例**：运行 `SpringMain`，观察日志输出
2. **查看 Zookeeper**：使用 Zookeeper 客户端工具查看节点变化
3. **断点调试**：在关键方法设置断点，如：
   - `JobScheduler.init()`
   - `LiteJob.execute()`
   - `AbstractElasticJobExecutor.execute()`
   - `ShardingService.shardingIfNecessary()`

### 6.3 关键配置说明

参考 `job.properties` 文件：

```1:22:elastic-job-example/elastic-job-example-lite-spring/src/main/resources/conf/job.properties
event.rdb.driver=org.h2.Driver
event.rdb.url=jdbc:h2:mem:job_event_storage
event.rdb.username=sa
event.rdb.password=

listener.simple=com.dangdang.ddframe.job.example.listener.SpringSimpleListener
listener.distributed=com.dangdang.ddframe.job.example.listener.SpringSimpleDistributeListener
listener.distributed.startedTimeoutMilliseconds=1000
listener.distributed.completedTimeoutMilliseconds=3000

simple.id=springSimpleJob
simple.class=com.dangdang.ddframe.job.example.job.simple.SpringSimpleJob
simple.cron=0/5 * * * * ?
simple.shardingTotalCount=3
simple.shardingItemParameters=0=Beijing,1=Shanghai,2=Guangzhou
simple.monitorExecution=false
simple.failover=true
simple.description=\u53EA\u8FD0\u884C\u4E00\u6B21\u7684\u4F5C\u4E1A\u793A\u4F8B
simple.disabled=false
simple.overwrite=true
simple.monitorPort=9888
```

---

## 七、总结

Elastic-Job 的整体架构清晰，采用**门面模式**和**策略模式**，核心流程：

1. **初始化**：创建调度器、注册到 Zookeeper、启动监听
2. **调度**：Quartz 根据 Cron 触发
3. **分片**：主节点执行分片，分配分片项
4. **执行**：各实例执行分配给自己的分片项
5. **监控**：通过 Zookeeper 监听器监控状态变化

**核心设计思想**：
- **分布式协调**：使用 Zookeeper 实现分布式协调
- **分片执行**：将大任务拆分成多个分片，并行执行
- **高可用**：通过失效转移、主节点选举等机制保证高可用
- **可扩展**：支持多种作业类型（Simple、Dataflow、Script）

通过理解这个流程，可以更好地阅读和扩展 Elastic-Job 源码。

