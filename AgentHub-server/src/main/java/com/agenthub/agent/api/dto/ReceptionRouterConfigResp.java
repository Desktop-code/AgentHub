package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 默认接待页「Router 网关配置」回显。所属域：agent。
 *
 * <p>目的只有一个：让运营在页面上看清「工作台首页那个 Router 现在用哪套模型、哪版 ROUTER 模板」，
 * 并能在同一处改。数据全部来自现网已有承载（{@code ma_def.config_json} 与 prompt 域的已发布模板），
 * 不新建网关配置表，也不复制一套提示词协议。
 *
 * <p>{@code judgeModelSource} 说明「实际生效」的模型是怎么来的：
 * <ul>
 *   <li>{@code SCHEME}：本方案显式绑定了实例（页面可改的就是它）；</li>
 *   <li>{@code SCHEME_ALIAS}：本方案用别名（{@code maJudgeModel}）指定，由执行侧按名字匹配；</li>
 *   <li>{@code PLATFORM}：未显式绑定，回落到平台 ROUTER / CHAT 能力槽；</li>
 *   <li>{@code NONE}：没有任何可用实例，工作台此时不可用。</li>
 * </ul>
 * 前端只读展示 {@code judgeModelName / modelId / capability}，把「现在用的是哪套」直接写出来。
 */
@Data
public class ReceptionRouterConfigResp {

    /** 默认接待方案主键；未配置时为 null */
    private Long schemeId;
    private String schemeCode;
    private String schemeName;
    /** 方案模板码；本刀起入口应为 ENTRY_ROUTER，页面据此展示「工作台入口」 */
    private String schemeTemplate;

    /** 方案是否可直接判路由（与默认接待就绪判定同源） */
    private boolean ready;
    /** 未就绪原因；ready=true 时为 null */
    private String reason;

    /** 方案显式绑定的实例 id；未显式绑定为 null */
    private Long judgeModelInstanceId;
    /** 实际生效实例的展示名；无可用实例时为空 */
    private String judgeModelName;
    /** 实际生效实例的供应商模型标识 */
    private String judgeModelModelId;
    /** 实际生效实例的用途码 */
    private String judgeModelCapability;
    /** 生效来源：SCHEME / SCHEME_ALIAS / PLATFORM / NONE */
    private String judgeModelSource;
    /** 方案上的模型别名（{@code maJudgeModel}）；未配置为空 */
    private String judgeModelAlias;

    /**
     * 当前 ROUTER 提示词绑定（模板、版本、soft 覆盖、追加区）。
     * 与专家配提示词同一份结构：前端复用同一个 L3 表单，不再另造字段。
     */
    private PromptBindingResp prompt;

    /** 钉死模板的展示名；未钉死或模板已失效时为空 */
    private String promptTemplateName;

    /** 钉死模板的业务键（一般为 ROUTER）；用于页面摘要 */
    private String promptTemplateKey;
}
