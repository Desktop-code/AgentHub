package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 方案编辑页「判路由模型」下拉项。所属域：agent。
 *
 * <p>只列启用中、用途属于对话形态的模型实例：嵌入 / 重排 / 视觉 / 语音拿来判路由跑不通，
 * 不在下拉里出现。字段与模型中心实例列表保持一致的最小集，避免页面再拿 id 去猜名称。
 *
 * <p>为什么单独给方案页一个接口而不是复用 {@code /api/model/instances}：
 * 维护方案的人通常只有 {@code ma:def:view/edit}，没有 {@code model:instance:list}；
 * 这里按权限与用途再筛一次，页面不必为了一个下拉去申请模型中心权限。
 */
@Data
public class MaJudgeModelOptionResp {

    /** model_instance.id，写入 config_json.maJudgeModelInstanceId */
    private Long id;
    /** 运营可读的实例名 */
    private String displayName;
    /** 供应商模型标识，如 gpt-4o-mini、qwen-plus */
    private String modelId;
    /** 用途码：ROUTER / CHAT / REWRITE / SUMMARY / MEMORY 等 */
    private String capability;
    /** 1 启用 / 0 停用；本接口只返回启用行，保留字段供前端统一渲染 */
    private Integer status;
}
