package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 可加入默认接待路由池的「另一套编排方案」选项。所属域：agent。
 *
 * <p>已剔除：自己、任一默认接待方案、停用方案、自己还挂着方案候选的方案（只允许下探一层）、
 * 以及模板不判路由的方案。方案说明为空仍会列出（否则运营不知道少了谁），
 * 但 {@code descriptionMissing=true} 时加不进池子，前端需明确标出。
 *
 * <p>为什么单列一个 DTO 而不复用 {@link ReceptionAgentOptionResp}：
 * 两者字段与语义都不同（方案没有 runMode，有的是 template），混用会让前端只能靠猜。
 */
@Data
public class ReceptionSchemeOptionResp {

    /** 方案主键，添加候选时作为 maDefId 提交 */
    private Long id;
    private String name;
    private String code;
    /** 方案模板码：ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE 等，前端只读展示 */
    private String template;
    /** 方案说明：加进池子后就是这条线路的意图说明 */
    private String description;
    /** true 表示还没填方案说明，添加会被拒绝 */
    private boolean descriptionMissing;
}
