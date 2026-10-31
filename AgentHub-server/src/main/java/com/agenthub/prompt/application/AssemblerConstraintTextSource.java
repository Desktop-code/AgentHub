package com.agenthub.prompt.application;

import com.agenthub.agent.domain.service.PlatformConstraintSource;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * L1 正文来源的实现：把 Assembler 的 L1 视图暴露给 agent 域。所属域：prompt。
 * <p>
 * 为什么要绕一层 Assembler 而不是直接读缓存：L1 是否启用、默认语言、无生效版时如何表现，
 * 这些口径必须与组装路径完全一致。多一个读法就会多一套行为
 * （例如配置页显示有前缀、出站校验却认为没有），正是架构定稿禁止的双轨。
 * <p>
 * 线程安全：无状态。
 */
@Component
public class AssemblerConstraintTextSource implements PlatformConstraintSource {

    private final PromptAssembler assembler;

    /**
     * @param assembler 组装门面；L1 的唯一读取入口
     */
    public AssemblerConstraintTextSource(PromptAssembler assembler) {
        this.assembler = assembler;
    }

    /**
     * {@inheritDoc}
     * 未发布时返回空——{@link com.agenthub.agent.domain.service.PlatformPromptPrefix}
     * 据此返回空串，进而让出站校验拒绝创建 Agent，而不是退回 classpath 文案。
     */
    @Override
    public Optional<String> activeConstraintText(String locale) {
        String text = assembler.constraintText(locale);
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}
