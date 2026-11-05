package com.agenthub.agent.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建 / 更新多智能体方案的请求。所属域：agent。
 *
 * <p>候选采用「嵌在方案里整份保存」：一次请求同时落方案与全部候选，
 * 避免前端为了保存一个模板而多次调用候选子资源。服务端按 ma_candidate 全量替换。
 */
@Data
public class MaDefSaveReq {

    @NotBlank(message = "方案编码不能为空")
    @Size(max = 64, message = "最长 64")
    private String code;

    @NotBlank(message = "方案名称不能为空")
    @Size(max = 128, message = "最长 128")
    private String name;

    /** 五值模板之一；服务端还会做白名单校验 */
    @NotBlank(message = "编排模板不能为空")
    @Size(max = 32, message = "最长 32")
    private String template;

    /** 与 Agent 运行配置同构；空则按模板默认补齐 */
    @Valid
    private AgentRuntimeConfigReq config;

    /** 绑定的 ROUTER 主键；空＝虚拟根（本刀只建档，不可试跑） */
    private Long rootAgentId;

    /** 1 启用 / 0 停用；空默认 1 */
    @Min(value = 0, message = "只能是 0 或 1")
    @Max(value = 1, message = "只能是 0 或 1")
    private Integer enabled;

    /** 候选整份保存；空列表表示清空候选 */
    @Valid
    @Size(max = 16, message = "单方案候选最多 16 个")
    private List<MaCandidateReq> candidates;
}
