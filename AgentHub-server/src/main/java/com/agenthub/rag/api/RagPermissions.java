package com.agenthub.rag.api;

/**
 * 知识库权限码。所属域：rag。
 * 与 sql/data-init-rag-menu.sql、接口注解、前端按钮使用同一套三段/两段码。
 * 域固定 rag，资源 kb，动作 list/add/edit/remove/rebuild。
 */
public final class RagPermissions {

    /** 列表与详情 */
    public static final String KB_LIST = "rag:kb:list";
    /** 新建 */
    public static final String KB_ADD = "rag:kb:add";
    /** 编辑资料与检索参数 */
    public static final String KB_EDIT = "rag:kb:edit";
    /** 删除 */
    public static final String KB_REMOVE = "rag:kb:remove";
    /** 重建集合（排障动作，单独授权） */
    public static final String KB_REBUILD = "rag:kb:rebuild";
    /** 文档列表 */
    public static final String DOC_LIST = "rag:doc:list";
    /** 上传文档 */
    public static final String DOC_UPLOAD = "rag:doc:upload";
    /** 重试入库 */
    public static final String DOC_RETRY = "rag:doc:retry";
    /** 删除文档 */
    public static final String DOC_REMOVE = "rag:doc:remove";
    /** 查看文档切片 */
    public static final String DOC_CHUNK = "rag:doc:chunk";
    /** 重新切片（按新片长重跑入库） */
    public static final String DOC_RECHUNK = "rag:doc:rechunk";

    private RagPermissions() {
    }
}
