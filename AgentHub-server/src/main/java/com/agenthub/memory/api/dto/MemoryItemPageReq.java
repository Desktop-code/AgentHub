package com.agenthub.memory.api.dto;

import com.agenthub.common.api.PageQuery;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 记忆分页。所属域：memory。
 * 控制台应传 agentId + scope；sessionId / userId / lifeStatus / 时间为选填。
 * 分页兼容 pageNum / pageSize，写入父类 current / size。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MemoryItemPageReq extends PageQuery {

    private Long agentId;
    private String scope;
    private Long sessionId;
    private Long userId;
    /** 兼容：库内 1 正常 / 0 停用。 */
    private Integer status;
    /** NORMAL / EXPIRED / DELETED。 */
    private String lifeStatus;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime createTimeFrom;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private LocalDateTime createTimeTo;

    /**
     * 前端 pageNum 别名 → current。
     *
     * @param pageNum 页码，从 1 起
     */
    public void setPageNum(long pageNum) {
        setCurrent(pageNum);
    }

    /**
     * 前端 pageSize 别名 → size。
     *
     * @param pageSize 每页条数
     */
    public void setPageSize(long pageSize) {
        setSize(pageSize);
    }
}
