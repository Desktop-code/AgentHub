package com.agenthub.prompt.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 左栏 L2 用途槽位摘要。所属域：prompt。
 * <p>
 * 一个 purpose 一条，对应左栏一张固定卡；templateKey 是该槽位当前绑定的业务键
 * （有生效版取生效行的 key，否则取版本最高的 key），空表示该用途尚无任何版本。
 * 存量同用途多 key 时只暴露其中一个，避免左栏出现两张同用途卡。
 */
@Data
public class PromptCatalogTemplateResp {

    /** 用途码，与 PromptPurpose 枚举同名，左栏槽位顺序即枚举顺序 */
    private String purpose;
    /** 该槽位绑定的业务键；空表示尚未配置 */
    private String templateKey;
    /** 绑定键的展示名；空槽位为空 */
    private String name;
    private String locale;
    /** 绑定键的版本数；0 表示空槽位 */
    private int versionCount;
    /** 最新版本行（版本号最大） */
    private Long latestId;
    private Integer latestVersion;
    /** 最新版本状态：0 草稿 1 已发布 */
    private Integer latestStatus;
    /** 当前生效版本；无生效行时为 null */
    private Long activeId;
    private Integer activeVersion;
    /** 该槽位内最晚更新时间 */
    private LocalDateTime updateTime;
}
