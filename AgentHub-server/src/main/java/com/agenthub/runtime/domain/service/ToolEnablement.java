package com.agenthub.runtime.domain.service;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentStatus;

/**
 * 工具是否本轮可调用。所属域：runtime。
 * 装配（进提示词工具目录与 LLM tools/schema）和运行时执行（注册可调用句柄）必须用同一套条件：
 * <b>工具源启用 ∧ 工具项启用 ∧ Agent 绑定启用</b>；任一为 0 或空都视为不可调用。
 * 禁止「schema 全量暴露、执行时再 skip」：模型会看到不可调用的名字并伪造文本 tool_call。
 */
public final class ToolEnablement {

    private ToolEnablement() {
    }

    /**
     * 绑定开关。停用的绑定连同目标一起排除，连目录都不进。
     *
     * @param bindStatus agent_tool_bind.status，1 正常 0 停用
     * @return true 表示该绑定参与本轮装配
     */
    public static boolean bindingOn(Integer bindStatus) {
        return bindStatus != null && bindStatus == AgentStatus.ENABLED;
    }

    /**
     * 工具项白名单状态。MCP 新同步项默认 0，必须人工启用后才可调用。
     *
     * @param itemStatus tool_item.status，1 正常 0 停用
     * @return true 表示工具项已进入白名单
     */
    public static boolean itemOn(Integer itemStatus) {
        return RecordStatus.isEnabled(itemStatus);
    }

    /**
     * 工具源状态。源停用等价于该源下全部工具本轮下线。
     *
     * @param sourceStatus tool_source.status，1 正常 0 停用
     * @return true 表示工具源可用
     */
    public static boolean sourceOn(Integer sourceStatus) {
        return RecordStatus.isEnabled(sourceStatus);
    }

    /**
     * 三项同时满足才允许进 schema，并允许注册成真正可调用的句柄。
     *
     * @param bindStatus   绑定状态
     * @param itemStatus   工具项状态
     * @param sourceStatus 工具源状态
     * @return true 表示本轮可调用
     */
    public static boolean callable(Integer bindStatus, Integer itemStatus, Integer sourceStatus) {
        return bindingOn(bindStatus) && itemOn(itemStatus) && sourceOn(sourceStatus);
    }
}
