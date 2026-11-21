package com.agenthub.runtime.domain.model;

import lombok.Data;

/**
 * 要注册进 AgentScope Toolkit 的内置工具。所属域：runtime。
 * name 必须与实现类方法上的 {@code @Tool(name)} 一致，装配时按这个名字找 Spring Bean。
 */
@Data
public class BuiltinToolSpec {

    /** tool_item.name，同时是 @Tool 的调用名 */
    private String name;

    /**
     * 本轮覆盖给模型的工具说明；空表示沿用 {@code @Tool(description)} 原文。
     * <p>
     * 为什么需要覆盖：内置检索工具的可用库清单来自当前 Agent 的绑定，随每轮装配变化，
     * 注解是编译期常量拼不出清单；因此由装配侧按现库生成整段说明放到这里，
     * 注册时用它替换 Bean 上看得到的那份描述。其它工具留空，行为与改造前一致。
     */
    private String descriptionOverride;
}
