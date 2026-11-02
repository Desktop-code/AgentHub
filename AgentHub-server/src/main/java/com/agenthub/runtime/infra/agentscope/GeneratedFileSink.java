package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.service.TrialProgress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一轮内的产物收集器。所属域：runtime 的 infra。
 *
 * <p>双重职责，缺一不可：
 * <ol>
 *   <li><b>实时</b>：立刻转发给 {@link TrialProgress#file}，前端不用等整轮结束就能看到下载卡片；</li>
 *   <li><b>留痕</b>：同时记一份按文件名去重（后者覆盖前者）的清单，
 *       整轮结束后由调用方写进 {@link com.agenthub.runtime.domain.model.TrialTurn}，
 *       再落成 FILE 事件——否则刷新页面后产物卡片就消失了。</li>
 * </ol>
 *
 * <p>线程：Router 并行跑多个子 Agent 时，工具回调分散在多个 boundedElastic 线程上，
 * 故所有写操作都加锁。读只在整轮收尾时发生一次（单线程），返回的是快照副本。
 */
public final class GeneratedFileSink implements Consumer<GeneratedFile> {

    /** 空出口：没有前端推送需求时用它占位，仍能收集产物用于落库 */
    private final TrialProgress progress;
    /** 文件名 → 产物。同名重复生成只保留最后一次，与前端去重口径一致 */
    private final Map<String, GeneratedFile> files = new LinkedHashMap<>();

    /**
     * @param progress 增量出口，可空；空则只收集不推送
     */
    public GeneratedFileSink(TrialProgress progress) {
        this.progress = progress;
    }

    /**
     * 收一个产物：先推送（失败不打断），再登记。
     *
     * @param file 产物；为空时忽略
     */
    @Override
    public void accept(GeneratedFile file) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            return;
        }
        // 推送放在登记之前：即使登记因并发问题抛错，前端也已经收到卡片
        if (progress != null) {
            try {
                progress.file(file);
            } catch (RuntimeException ex) {
                // 出站已断（客户端关闭页面）：不影响落库留痕
            }
        }
        synchronized (files) {
            files.put(file.fileName(), file);
        }
    }

    /**
     * 取走已收集的产物，按首次出现顺序。
     *
     * @return 不可变快照；没有产物时为空列表
     */
    public List<GeneratedFile> snapshot() {
        synchronized (files) {
            return Collections.unmodifiableList(new ArrayList<>(files.values()));
        }
    }
}
