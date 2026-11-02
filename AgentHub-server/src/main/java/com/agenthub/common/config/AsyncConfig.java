package com.agenthub.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 全局线程池装配。所属域：common 的 config。
 * <p>
 * 为什么集中在一处：此前 Router 并行子 Agent 每次请求 {@code new} 临时池、MCP SSE 读流裸
 * {@code new Thread}，线程数量随并发请求线性放大且不受任何全局上限约束。这里把三类异步场景
 * 各收口成一个命名 Bean，参数（核心/最大/队列/拒绝策略）只在此文件维护，业务侧按名注入即可。
 * <p>
 * 硬约束：
 * <ol>
 *   <li>三个池<b>队列均为有界队列</b>，且队列容量只在此文件维护；调用方不得另建无界队列池覆盖；</li>
 *   <li>{@code @EnableAsync} 在此开启，同时通过 {@link AsyncConfigurer#getAsyncExecutor()} 把
 *       <b>默认执行器</b>钉到 {@link #lightTaskExecutor()}：不显式写 {@code @Async("xxx")} 的任务
 *       也不会退回到 Spring 默认的无界 {@code SimpleAsyncTaskExecutor}（那会「每次调用新建线程」）；</li>
 *   <li>各 Bean 已显式命名，注入方必须用 {@code @Qualifier} 指定，禁止按类型盲注
 *       （容器里还有 {@code ragIngestExecutor}，按类型注入会歧义）。</li>
 * </ol>
 * 停机行为：沿用 {@link ThreadPoolTaskExecutor} 默认值（不等待任务跑完，直接中断），
 * 因此池内任务必须能被中断；阻塞出站调用应各自带超时，不能指望停机时被优雅收尾。
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** Router 池线程名前缀，排障时按前缀区分来源 */
    private static final String ROUTER_THREAD_PREFIX = "router-task-";

    /** MCP 读流池线程名前缀 */
    private static final String MCP_READER_THREAD_PREFIX = "mcp-reader-";

    /** 后台轻量任务池线程名前缀 */
    private static final String LIGHT_THREAD_PREFIX = "light-task-";

    /**
     * Router 并行子 Agent 执行池。
     * <p>
     * 用途：替换 {@code RouterTrialExecutor#runChildren} 里每次请求新建的临时池，
     * 承接「一次试跑按意图并行跑多个子 Agent」的任务，每个子任务内部是一轮完整 ReAct（含阻塞模型调用）。
     * <p>
     * 为什么最大线程只给 20：子任务会长时间占用线程做外部流式调用，线程数必须与 Tomcat 线程数
     * 以及下游模型配额一起看，不能按「请求数 × 子 Agent 数」无上限放开。
     * 为什么拒绝策略选 CallerRuns：试跑是用户明确发起的同步请求，池满时退回提交线程（Tomcat 线程）执行，
     * 表现为「变慢」而不是「子 Agent 直接失败」，语义上更可接受。
     *
     * @return 有界队列（500）+ CallerRuns 的执行器，核心 10 / 最大 20
     */
    @Bean("routerTaskExecutor")
    public ThreadPoolTaskExecutor routerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix(ROUTER_THREAD_PREFIX);
        // 池满时不丢弃子任务：退回提交线程（试跑请求线程）执行，宁可整轮变慢也不让某个子 Agent 凭空失败
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * MCP SSE 读流池。
     * <p>
     * 用途：替换 {@code McpSseClient#withSession} 里为每条 SSE 会话裸起的读流线程。
     * 该任务形态是「阻塞 readLine 直到流结束」，只能靠关流/中断退出，因此线程数刻意压小。
     * <p>
     * 为什么拒绝策略选 AbortPolicy：调用方（工具目录同步、tools/call）是同步等待结果的控制台动作，
     * 池与队列都满说明 MCP 侧已经堵死；此时立刻抛 {@code TaskRejectedException} 让接口快速失败并提示重试，
     * 比 CallerRuns 把 Tomcat 线程也拖进长时间阻塞要好。
     * daemon=true 保留原有裸线程的守护语义：即便容器关闭时读流线程卡在 readLine，也不会阻止 JVM 退出。
     *
     * @return 有界队列（100）+ Abort 的执行器，核心 5 / 最大 10
     */
    @Bean("mcpReaderExecutor")
    public ThreadPoolTaskExecutor mcpReaderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(MCP_READER_THREAD_PREFIX);
        // 队列满时直接拒绝：丢任务比拖死调用线程好，调用方按失败重试
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 与改造前的守护线程保持一致，避免读流线程卡住时拖住进程退出
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    /**
     * 后台轻量任务池。
     * <p>
     * 用途：承接「不阻塞用户响应、失败可降级」的短任务，如生成会话标题等。
     * 约束：只放纯 DB / 短出站任务，禁止把整轮试跑或长耗时向量化丢进来；
     * 需要长时间占用线程的任务请走 {@link #routerTaskExecutor()} 或 {@code ragIngestExecutor}。
     * <p>
     * 拒绝策略选 CallerRuns：轻量任务丢失会造成数据不一致（如标题缺失），
     * 退回到提交线程执行的代价可控，优于静默丢弃。
     *
     * @return 有界队列（200）+ CallerRuns 的执行器，核心 2 / 最大 5
     */
    @Bean("lightTaskExecutor")
    public ThreadPoolTaskExecutor lightTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix(LIGHT_THREAD_PREFIX);
        // 轻量任务不丢：队列满时退回提交线程执行，避免出现「有轮次没标题」的残缺数据
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * {@code @Async} 未指定执行器名时的兜底池。返回 light 池而不是 Spring 默认的
     * {@code SimpleAsyncTaskExecutor}：默认实现每次调用新建线程且无上限，一个漏写名字的
     * 注解就能把线程数打爆。钉到这里后，所有异步任务都受同一个有界池约束。
     *
     * @return 后台轻量任务池
     */
    @Override
    public Executor getAsyncExecutor() {
        return lightTaskExecutor();
    }

    /**
     * 异步方法抛出的异常兜底。业务侧的 {@code @Async} 方法都应自行 catch（标题生成即如此），
     * 这里只保证「漏 catch 也不会静默丢失」：打一条 warn，不往上抛、不让线程终止。
     *
     * @return 记录方法名与异常类型的处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.warn("异步任务未捕获异常 method={} type={}",
                method == null ? "unknown" : method.getName(), ex.getClass().getSimpleName(), ex);
    }
}
