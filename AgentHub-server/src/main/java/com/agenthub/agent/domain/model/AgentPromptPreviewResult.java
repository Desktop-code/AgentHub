package com.agenthub.agent.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * L3 预览结果。所属域：agent。
 * <p>
 * 与 {@code PromptAssemblyResult} 字段对齐但不直接复用：agent 域不应依赖 prompt 域的模型类，
 * 只通过 {@link com.agenthub.agent.domain.service.AgentPromptTemplatePort} 取回纯数据。
 * <p>
 * 约束：{@code text} 是**走 PromptAssembler 真实组装路径**得到的正文（含 L1 / L2 / L3），
 * 不调用任何模型；版本号必须回传，否则无法解释「预览用的是哪一版」。
 */
@Data
public class AgentPromptPreviewResult {

    /** 组装后的正文 */
    private String text;
    /** 实际命中的用途 */
    private String purpose;
    /** 实际语言 */
    private String locale;
    /** L1 生效版本；0 表示 L1 关闭（排障态） */
    private Integer constraintVersion;
    /** L2 业务键 */
    private String templateKey;
    /** L2 实际使用的版本 */
    private Integer templateVersion;
    /** 拼入的段落数 */
    private Integer segmentCount;
    /** 被 L3 覆盖的 soft 段数 */
    private Integer overriddenSegmentCount;
    /** 是否按预算截断 */
    private boolean truncated;
    /** 非致命说明（如失效 key 被忽略） */
    private List<String> notices = new ArrayList<>();
}
