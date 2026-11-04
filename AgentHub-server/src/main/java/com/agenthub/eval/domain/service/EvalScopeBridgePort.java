package com.agenthub.eval.domain.service;

import com.agenthub.eval.domain.model.EvalScopeBridgeResult;
import com.agenthub.eval.domain.model.EvalScopeExportRequest;

/**
 * EvalScope 可选桥端口。所属域：eval 的 domain。
 * <p>调用方：{@link com.agenthub.eval.application.EvalScopeBridgeAppService}（显式 API）。
 * 实现方：infra {@code EvalScopeBridgeAdapter}（进程外：落盘导出 + 可选 HTTP sidecar）。
 * <p>契约：
 * <ul>
 *   <li>本 Port <b>不</b>判断 enabled（由应用层短路）；进入即视为已启用；</li>
 *   <li>{@link #export}：固定路径覆盖写 {@code manifest.json}；</li>
 *   <li>{@link #trigger}：仅 HTTP；无 base_url → SKIPPED；超时/5xx → ERROR；</li>
 *   <li>失败 soft，禁止改 gate/verdict；禁止同进程嵌 Python。</li>
 * </ul>
 * 线程：实现须可并发调用（无共享可变状态，或自行同步）。
 */
public interface EvalScopeBridgePort {

    /**
     * 将清单落盘到 {@code exportDir/manifest.json}（目录不存在则创建；同路径覆盖）。
     *
     * @param request 含 exportDir + manifest
     * @return OK+path 或 ERROR
     */
    EvalScopeBridgeResult export(EvalScopeExportRequest request);

    /**
     * 触发外部 EvalScope 任务（HTTP sidecar）。不落盘。
     *
     * @param request 含 baseUrl / timeout / clientToken / benchmark
     * @return OK+jobId / SKIPPED / ERROR
     */
    EvalScopeBridgeResult trigger(EvalScopeExportRequest request);
}
