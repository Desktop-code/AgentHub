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
}
