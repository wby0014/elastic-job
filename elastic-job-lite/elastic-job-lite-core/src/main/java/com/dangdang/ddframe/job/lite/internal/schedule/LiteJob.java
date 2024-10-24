package com.dangdang.ddframe.job.lite.internal.schedule;

import com.dangdang.ddframe.job.api.ElasticJob;
import com.dangdang.ddframe.job.executor.JobExecutorFactory;
import com.dangdang.ddframe.job.executor.JobFacade;
import lombok.Setter;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Lite调度作业.
 *
 * @author zhangliang
 */
public final class LiteJob implements Job {

    /**
     * quartz会在job作业执行前自动注入属性值，依据的是在创建jobDetail时设置了JobDataMap中存储的数据
     */
    @Setter
    private ElasticJob elasticJob;
    
    @Setter
    private JobFacade jobFacade;

    /**
     * lite作业被调度时，执行该方法，进而处理elastic-job的分片等逻辑
     * @param context
     * @throws JobExecutionException
     */
    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException {
        JobExecutorFactory.getJobExecutor(elasticJob, jobFacade).execute();
    }
}
