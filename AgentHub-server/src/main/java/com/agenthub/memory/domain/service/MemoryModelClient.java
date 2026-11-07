package com.agenthub.memory.domain.service;

/**
 * 摘要和长期偏好抽取的出站调用。所属域：memory。
 * 实现在 runtime，走模型管理和 BillingMeter，禁止在这里写死厂商。
 */
public interface MemoryModelClient {

    /**
     * 基础调用：{@code instruction} 作为系统段，{@code source} 作为待处理正文。
     * <p>
     * 记忆抽取（L2）的 {@code instruction} 由平台锁定段组装后传入；L1 分类、摘要、
     * 标题生成仍直接传各自代码常量。需要单独透传采样参数时走带系统段的重载。
     *
     * @param agentId         当前 Agent，计量用
     * @param sessionId       试跑会话，可空
     * @param userId          平台用户
     * @param modelInstanceId 已解析的实例
     * @param instruction     系统段（平台锁定段或代码常量）
     * @param source          待处理正文，不得含密钥
     * @return 模型文本
     */
    String complete(long agentId, Long sessionId, long userId, long modelInstanceId, String instruction, String source);

    /**
     * 带系统段与采样参数的调用：系统段在前，场景说明紧随其后，正文作为用户消息。
     * <p>
     * <b>格式红线</b>：{@code systemInstruction} 只能来自平台锁定段——
     * L1 是分类代码常量，L2 是「记忆抽取模板」的锁定正文；{@code scenarioExtra}
     * 只能是 MEMORY L3 的追加区，拼在系统段之后，不能整份替换锁定段。
     * L1 分类不应传 {@code scenarioExtra}，避免抽取格式/追加要求影响分类。
     * <p>
     * 默认实现把场景说明拼到系统段后、忽略采样参数，便于测试替身与不关心采样参数的实现；
     * 真实实现必须覆盖本方法并把 {@code temperature/maxTokens} 透传给模型。
     *
     * @param agentId         当前 Agent，计量用
     * @param sessionId       试跑会话，可空
     * @param userId          平台用户
     * @param modelInstanceId 已解析的实例
     * @param systemInstruction 平台锁定任务说明（系统段）
     * @param scenarioExtra    MEMORY L3 追加区（场景说明），可空
     * @param source           待处理正文，不得含密钥
     * @param temperature      采样温度，可空表示不指定
     * @param maxTokens        输出上限，可空表示不指定
     * @return 模型文本
     */
    default String complete(long agentId, Long sessionId, long userId, long modelInstanceId,
                            String systemInstruction, String scenarioExtra, String source,
                            Double temperature, Integer maxTokens) {
        String system = scenarioExtra == null || scenarioExtra.isBlank()
                ? systemInstruction
                : systemInstruction + "\n\n" + scenarioExtra.trim();
        return complete(agentId, sessionId, userId, modelInstanceId, system, source);
    }

    /**
     * 出站目标描述（实例名、模型、用途、脱敏地址），<b>只供日志</b>。
     * <p>
     * 默认返回 unknown：测试替身与不关心日志的实现不必覆盖；调用方打日志前必须容忍 null。
     *
     * @param modelInstanceId 已解析的实例，可空
     * @return 描述；实现不应返回 null
     */
    default Outbound describe(Long modelInstanceId) {
        return Outbound.unknown(modelInstanceId);
    }

    /**
     * 一次记忆出站的目标。所属域：memory。
     * <p>
     * <b>脱敏红线</b>：{@code endpoint} 只允许 host + path，
     * 不得包含 apiKey / Authorization / userinfo / query（签名常在 query 里）。
     *
     * @param instanceId  模型实例 id
     * @param displayName 实例显示名，可空
     * @param modelId     厂商模型名，可空
     * @param capability  用途（CHAT / MEMORY / SUMMARY…），可空
     * @param protocol    协议（OPENAI_COMPAT / DASHSCOPE / ANTHROPIC…），可空
     * @param endpoint    脱敏后的地址（host + path），可空
     */
    record Outbound(Long instanceId, String displayName, String modelId, String capability,
                    String protocol, String endpoint) {

        /**
         * @param instanceId 实例 id，可空
         * @return 字段全空的描述，便于日志占位
         */
        public static Outbound unknown(Long instanceId) {
            return new Outbound(instanceId, null, null, null, null, "");
        }

        /**
         * @return 日志用单行摘要，如 {@code name=x model=qwen-plus capability=MEMORY protocol=DASHSCOPE endpoint=…}
         */
        public String describeLine() {
            return "name=" + blankToDash(displayName)
                    + " model=" + blankToDash(modelId)
                    + " capability=" + blankToDash(capability)
                    + " protocol=" + blankToDash(protocol)
                    + " endpoint=" + blankToDash(endpoint);
        }

        private static String blankToDash(String value) {
            return value == null || value.isBlank() ? "-" : value.trim();
        }
    }
}
