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
}
