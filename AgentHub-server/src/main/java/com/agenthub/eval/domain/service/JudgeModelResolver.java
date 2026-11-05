package com.agenthub.eval.domain.service;

/**
 * 打分模型凭据解析端口。所属域：eval 的 domain（仅接口）。
 * <p>调用方：{@code EvalRunnerAppService} 在软评分分支按 run 快照里的
 * {@code judge_model_instance_id} 取值；实现方：eval 的 infra（复用 model 域现有
 * AES-GCM 解密与「按 instanceId 取供应商/凭证」能力，<b>不得</b>另造密钥库）。
 * <p>线程：实现应为无状态，可并发调用；返回对象只在本轮请求内活。
 */
public interface JudgeModelResolver {

    /**
     * 解出该实例的 Judge 凭据。
     *
     * @param instanceId 模型实例 id（本 run 显式选中的打分模型）
     * @return 含 model / base_url / api_key 的值对象
     * @throws JudgeModelUnavailableException 实例不存在、已停用、缺凭证或解密失败；
     *                                       调用方据此把该 case 记 ERROR，<b>禁止</b>回落全局 Key
     */
    com.agenthub.eval.domain.model.OpenJudgeJudge resolve(Long instanceId);
}
