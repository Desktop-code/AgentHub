package com.agenthub.runtime.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 试跑事件。所属域：runtime。summary 和 contentText 都不含密钥和工具实参。
 */
@Data
public class EventResp {

    private Long id;
    private Long sessionId;
    private Integer seq;
    private String eventType;
    private String summary;
    /** 展示正文。助手为 Markdown，推理为思考片段，工具为结果摘要 */
    private String contentText;
    private Integer tokenPrompt;
    private Integer tokenCompletion;
    private LocalDateTime createTime;
}
