package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 提示词管理端左栏目录。所属域：prompt。
 * 一次请求给出单 locale 下的 L1 版本摘要与 L2 模板摘要，替代左栏多次列表请求。
 * 不含段落正文，也不做任何拼装/变量替换（PromptAssembler 不在此接线）。
 */
@Data
public class PromptCatalogResp {

    /** 本次目录的语言（空白入参已回落 zh-CN） */
    private String locale;
    /** L1 该 locale 全部版本摘要，按 version 倒序 */
    private List<PromptCatalogConstraintResp> constraints = new ArrayList<>();
    /** L2 模板摘要，每个 template_key 一条 */
    private List<PromptCatalogTemplateResp> templates = new ArrayList<>();
}
