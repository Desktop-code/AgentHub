package com.agenthub.agent.domain.service;

import java.util.Optional;

/**
 * L1 平台约束正文来源（端口）。所属域：agent。
 * <p>
 * 为什么在 agent 域定义这个口：平台前缀是 Agent 配置与出站校验都要用的东西，
 * 但 L1 的存储与组装属于 prompt 域。若让 agent 直接依赖 prompt 的实现类，
 * 两个域会互相引用（prompt 的 Adapter 又要读 AgentDef）。因此在 agent 侧只留接口，
 * 由 prompt 域实现——依赖方向固定为 prompt → agent。
 * <p>
 * 实现方：{@code com.agenthub.prompt.application.AssemblerConstraintTextSource}，
 * 内部走 {@code PromptAssembler}，与出站组装共用同一份 L1 口径。
 * <p>
 * 约束：**禁止**用 classpath 文件或硬编码文案兜底。未发布时返回空，
 * 由调用方阻断出站（「没模板还继续聊」是明令禁止的降级）。
 * 线程安全：实现须可并发调用。
 */
@FunctionalInterface
public interface PlatformConstraintSource {

    /**
     * @param locale 语言；空白表示用平台默认
     * @return 当前生效 L1 正文；未发布时为空
     */
    Optional<String> activeConstraintText(String locale);
}
