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
 * {@code new Thread}，线程数量随并发请求线性放大且不受任何全局上限约束。这里把各类异步场景
 * 各收口成一个命名 Bean，参数（核心/最大/队列/拒绝策略）只在此文件维护，业务侧按名注入即可。
 * <p>
 * 硬约束：
 * <ol>
 *   <li>各个池<b>队列均为有界队列</b>，且队列容量只在此文件维护；调用方不得另建无界队列池覆盖；</li>
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

    /** 显式「记住」抽取池线程名前缀 */
    private static final String MEMORY_THREAD_PREFIX = "memory-task-";

    /** 记忆召回（Embedding / Rerank / 向量检索）池线程名前缀 */
    private static final String MEMORY_RECALL_THREAD_PREFIX = "memory-recall-";

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
     * 显式「记住」的长期偏好抽取池。
     * <p>
     * 用途：用户说「请记住…」时，抽取要走一次最长 60s 的 MEMORY 出站。这一步必须离开请求线程，
     * 否则首字延迟被整段抽取吃掉（N6 红线）。本池只承接 {@code ExplicitMemoryExtractor} 投递的任务。
     * <p>
     * 为什么不复用 {@link #lightTaskExecutor()}：light 池是 CallerRuns，池满时任务会退回<b>提交线程</b>执行；
     * 对本场景等于「高并发下偶发地又把 60s 阻塞塞回 Tomcat 线程」，红线会复发。且 light 池的既有注释
     * 明确只放纯 DB / 短出站任务，抽取属于长出站，不该挤占标题生成。
     * <p>
     * 为什么拒绝策略选 Abort：这里<b>必须</b>保证「提交方立刻返回」。池与队列都满时宁可丢掉这一次
     * 记忆写入（用户重申一次、人工录入、会话结束批抽都能补），也绝不能退回请求线程等模型。
     * 丢弃由投递方（{@code ExplicitMemoryExtractor}）捕获 {@code TaskRejectedException} 并打 warn，
     * 带 sessionId，便于事后确认「用户说过记住但没落库」是容量问题而不是规则问题。
     * <p>
     * 容量依据：单任务最长 60s，核心 4 / 最大 8 覆盖控制台试跑量；队列 200 有界，
     * 饱和后立即拒绝而不是无界堆积吃内存。停机沿用默认（不等待、直接中断），
     * 被中断的抽取等于少一次记忆写入，不影响已经发出的回答。
     *
     * @return 有界队列（200）+ Abort 的执行器，核心 4 / 最大 8
     */
    @Bean("memoryTaskExecutor")
    public ThreadPoolTaskExecutor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix(MEMORY_THREAD_PREFIX);
        // 拒绝即丢弃：本池任务绝不允许退回调用线程，否则 60s 抽取会重新阻塞用户首字
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 记忆召回出站池（Embedding / Rerank / 向量检索）。
     * <p>
     * 用途：LONG 语义召回要在主对话前完成，但每次出站都必须有等待上限。调用线程（Tomcat）
     * 把出站提交到本池后只等 {@code agenthub.memory.recall-*-timeout-ms}，超时即降级 MySQL Top N。
     * 这样「出站能不能快点返回」不再决定首字延迟的上界。
     * <p>
     * 为什么不复用 {@link #lightTaskExecutor()}：light 是 CallerRuns，池满时会把出站退回请求线程，
     * 超时上限立刻失效；召回宁可降级也不能把出站塞回主线程。
     * 为什么不复用 {@link #memoryTaskExecutor()}：那是 60s 抽取池且用 AbortPolicy 丢任务，
     * 与「召回失败要回落 MySQL」的语义不同，也不该互相挤占。
     * <p>
     * 为什么 Abort：池与队列都满说明召回侧已经过载，立即降级比排队等待更能保住主对话。
     * daemon=true：停机时召回任务不应拖住进程退出（出站本身也有读超时）。
     *
     * @return 有界队列（100）+ Abort 的执行器，核心 4 / 最大 8
     */
    @Bean("memoryRecallExecutor")
    public ThreadPoolTaskExecutor memoryRecallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix(MEMORY_RECALL_THREAD_PREFIX);
        // 池满即降级：绝不把召回出站退回请求线程（那会让超时上限失效）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setDaemon(true);
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
