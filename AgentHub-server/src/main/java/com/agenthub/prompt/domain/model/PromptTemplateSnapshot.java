package com.agenthub.prompt.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 某一「已发布版本」的完整模板快照（主表 + 段落）。所属域：prompt。
 * <p>
 * 缓存单位就是本类：按 {@code template_key + locale + version} 存，钉死版本的 Agent
 * 只读自己那一份，平台发新版不影响它（版本漂移防护）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromptTemplateSnapshot {

    private Long id;
    private String templateKey;
    /** 用途码；与调用用途不一致时必须阻断，禁止拿 CHAT 模板顶替 */
    private String purpose;
    private String locale;
    private String protocol;
    private Integer version;
    private List<PromptSegmentSnapshot> segments = new ArrayList<>();

    /**
     * @return 段落按 sort_no 升序的副本；sort_no 相同保持库内 id 顺序
     */
    public List<PromptSegmentSnapshot> orderedSegments() {
        List<PromptSegmentSnapshot> copy = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        copy.sort((left, right) -> {
            int a = left.getSortNo() == null ? 0 : left.getSortNo();
            int b = right.getSortNo() == null ? 0 : right.getSortNo();
            return Integer.compare(a, b);
        });
        return copy;
    }

    /**
     * 组装用的两相位排序：先全部锁定段（mutable=0），再全部开放段（mutable=1）；相位内仍按 sort_no。
     * <p>
     * 为什么不直接用 {@link #orderedSegments()}：库里 sort_no 是人工填的数字，开放段一旦填得比
     * 锁定段小（如 output_contract 被写成 30、role_tone 写成 10），锁定契约就会被挤到业务语气之后——
     * 模型先读「怎么说」再读「必须怎么输出」，红线形同虚设。相位优先由代码保证，不依赖数据碰巧排对。
     * <p>
     * 实现说明：{@link List#sort} 是稳定排序，因此只在相位维度比较即可保留相位内的 sort_no 顺序。
     *
     * @return 锁定段在前、开放段在后的副本
     */
    public List<PromptSegmentSnapshot> orderedSegmentsByPhase() {
        List<PromptSegmentSnapshot> copy = orderedSegments();
        copy.sort((left, right) -> Integer.compare(left.soft() ? 1 : 0, right.soft() ? 1 : 0));
        return copy;
    }
}
