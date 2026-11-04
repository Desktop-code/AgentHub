package com.agenthub.eval.api.dto;

import lombok.Data;

/**
 * 导入结果出参。所属域：eval。
 * <p>语义：一次成功的导入只产生**一个新版本**，旧版本用例行原样保留。
 * previousVersion/currentVersion 便于调用方判断是否真的升了版（如首建时 previous 为空）。
 */
@Data
public class EvalImportResp {

    private Long suiteId;
    private String suiteKey;
    /** 导入前的当前版本；新建套件时无意义（为 null） */
    private Integer previousVersion;
    /** 导入后的当前版本 */
    private Integer currentVersion;
    /** 新版本写入的用例数 */
    private Integer caseCount;
}
