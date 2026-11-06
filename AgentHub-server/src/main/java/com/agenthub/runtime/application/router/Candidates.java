package com.agenthub.runtime.application.router;

import java.util.List;

/**
 * 候选装载结果：可用与被挡下两组。所属域：runtime。
 *
 * <p>两条链都要用：可用候选进判路由/固定模板，被挡下的候选只进时间线（写「已跳过」原因），
 * 不参与终答拼接、不计 token。
 *
 * @param usable  通过 allow_orchestrated 与运行模式校验的候选
 * @param blocked 被挡下的候选及中文原因
 */
public record Candidates(List<Candidate> usable, List<BlockedCandidate> blocked) {
}
