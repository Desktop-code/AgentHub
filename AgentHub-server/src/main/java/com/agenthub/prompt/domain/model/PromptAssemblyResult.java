package com.agenthub.prompt.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装结果。所属域：prompt。
 * <p>
 * 除了最终正文，还带回「用了哪一版」与各段字符数，供观测与排障：
 * 版本号必须可见——否则线上变脸时无法回答「当时跑的是哪一版模板」。
 * 正文可能含用户业务描述，禁止整段进日志。
 */
@Data
public class PromptAssemblyResult {

    /** 最终 system / prompt 正文（已做一次性变量替换） */
    private String text;
    /** 命中用途 */
    private String purpose;
    /** 实际使用的语言 */
    private String locale;
    /** L1 生效版本；0 表示 L1 关闭（仅测试用） */
    private Integer constraintVersion;
    /** L2 模板主键 */
    private Long templateId;
    /** L2 业务键 */
    private String templateKey;
    /** L2 实际使用的版本（钉死版或生效版） */
    private Integer templateVersion;
    /** 拼进去的段落数（core + soft） */
    private int segmentCount;
    /** 其中被 L3 覆盖的 soft 段数 */
    private int overriddenSegmentCount;
    /** L2 之后追加的历史用户业务段字符数 */
    private int businessChars;
    /** L3 追加区字符数 */
    private int extraChars;
    /** 是否按预算截断过（只可能发生在可截断 tail） */
    private boolean truncated;
    /**
     * 结构描述：受保护前缀长度 + 各段渲染后正文。
     * 下游（Runtime 注入层、出站校验）据此保证「L1 与锁定段不被二次截断」「锁定段在开放段之前」。
     */
    private PromptLayout layout = PromptLayout.EMPTY;
    /** 组装过程中的非致命说明（如 override 里的未知段被忽略） */
    private List<String> notices = new ArrayList<>();
}
