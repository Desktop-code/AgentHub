package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划视图。所属域：runtime。
 * 字段与 PLAN 事件正文（content_text 的 JSON）一致，前端回放与接口查询共用同一套解析。
 */
@Data
public class PlanResp {

    /** 计划标识，同一轮内稳定；状态更新沿用同一 planId */
    private String planId;

    /** PENDING / EXECUTING / REJECTED / DONE / FAILED */
    private String state;

    /** 人话摘要，例如「计划共 3 步 · 待批准」 */
    private String summary;

    /** 按 seq 升序的步骤 */
    private List<Step> steps = new ArrayList<>();

    /** 单步定义。执行结果不在这里，走 STEP 事件。 */
    @Data
    public static class Step {
        private Integer seq;
        private String title;
        private String detail;
    }
}
