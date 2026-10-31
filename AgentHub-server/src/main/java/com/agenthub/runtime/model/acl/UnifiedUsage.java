package com.agenthub.runtime.model.acl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一用量统计。所属域：runtime（模型出站 ACL）。
 * 计费与观测只读本结构；上游没有返回用量时字段可为 null，由计费侧按既定估算策略兜底，
 * 禁止在 Adapter 里伪造 0 掩盖缺失。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedUsage {

    /** 输入 token（提示词侧） */
    private Integer prompt;

    /** 输出 token（生成侧） */
    private Integer completion;

    /** 总 token。上游只给部分值时可为 null，不在此类里做加总推断 */
    private Integer total;
}
