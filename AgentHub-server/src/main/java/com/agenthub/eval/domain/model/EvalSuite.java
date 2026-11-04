package com.agenthub.eval.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评测套件。所属域：eval。对应表 eval_suite。
 * <p>关键约束：
 * <ul>
 *   <li>{@code tenantId=0} 是平台内置样例，应用层只读；租户用例用真实租户 id。</li>
 *   <li>{@code currentVersion} 是「可读的当前版本」；版本号不可回退，改内容一律 +1。</li>
 *   <li>{@code defaultConfigJson} 是 JSON 文本，读写按字符串处理（与 rag 域 JSON 列一致）。</li>
 * </ul>
 */
@Data
public class EvalSuite {

    /** 平台内置样例租户；该租户的行只读，不能改也不能删 */
    public static final long PLATFORM_TENANT_ID = 0L;

    /** 当前启用的用例状态值，与全站 status 口径一致 */
    public static final int ENABLED = 1;
    /** 停用 */
    public static final int DISABLED = 0;

    /** 主键 */
    private Long id;
    /** 租户；0 为平台只读样例 */
    private Long tenantId;
    /** 租户内逻辑键，如 platform.smoke.rag */
    private String suiteKey;
    /** 中文名 */
    private String name;
    /** 说明 */
    private String description;
    /** 当前版本号，从 1 开始 */
    private Integer currentVersion;
    /** 默认配置 JSON（并发/超时/scorer 开关），可空 */
    private String defaultConfigJson;
    /** 1 启用 0 停用 */
    private Integer enabled;
    /** 创建人 */
    private Long createdBy;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 更新人 */
    private Long updatedBy;
    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 是否平台内置样例（租户用户不可写）。
     *
     * @return true 表示只读样例
     */
    public boolean isPlatformOwned() {
        return tenantId != null && tenantId == PLATFORM_TENANT_ID;
    }
}
