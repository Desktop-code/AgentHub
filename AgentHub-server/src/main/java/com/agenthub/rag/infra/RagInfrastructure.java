package com.agenthub.rag.infra;

import com.agenthub.rag.infra.milvus.RagMilvusProperties;
import com.agenthub.rag.infra.parse.RagParseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * RAG 基础设施装配。所属域：rag 的 infra。
 * <p>
 * 只注册配置属性与入库线程池；Milvus 客户端按需懒建，见 {@code MilvusClientHolder}，不在启动期连接。
 * <p>
 * 入库线程池刻意做小：向量化是外部调用，并发过高容易把厂商限流打满，
 * 也会与本服务的检索请求抢连接。队列满时用 CallerRuns 让提交方自己跑，
 * 保证任务不丢——上传接口本来就已返回 taskId，跑在请求线程上只是慢一点，不会丢任务。
 */
@Configuration
@EnableConfigurationProperties({RagMilvusProperties.class, RagParseProperties.class})
public class RagInfrastructure {

    /**
     * 入库线程池。名字固定 {@code ragIngestExecutor}，与 {@code RagIngestAppService} 的 @Qualifier 对应。
     *
     * @return 有界队列 + CallerRuns 的执行器
     */
    @Bean("ragIngestExecutor")
    public TaskExecutor ragIngestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("rag-ingest-");
        // 队列满时不丢弃：退回调用线程执行，宁可慢也不静默丢任务
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
