package com.agenthub.runtime.infra.agentscope.builtin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 补 {@code @ToolParam} 写不出的 JSON Schema 数值约束。所属域：runtime。
 * AgentScope 只把名称、说明、是否必填送进模型；minimum / maximum / default 由本注解读给 {@code BuiltinToolSchemas}。
 * 未设置的边界用 {@link Long#MIN_VALUE} / {@link Long#MAX_VALUE} 占位，生成器不会写进 schema。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BuiltinParam {

    /**
     * @return JSON Schema minimum；默认 {@link Long#MIN_VALUE} 表示不输出
     */
    long minimum() default Long.MIN_VALUE;

    /**
     * @return JSON Schema maximum；默认 {@link Long#MAX_VALUE} 表示不输出
     */
    long maximum() default Long.MAX_VALUE;

    /**
     * @return 默认值文本。空则不输出 default；整数参数会写成数字
     */
    String defaultValue() default "";
}
