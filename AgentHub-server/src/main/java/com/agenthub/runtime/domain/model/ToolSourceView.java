package com.agenthub.runtime.domain.model;

import com.agenthub.tool.domain.model.ToolSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具源的无密投影。所属域：runtime。
 * <p>
 * 为什么单独建一个投影而不是直接缓存 {@code ToolSource}：
 * 装配链只需要「类型 + 启停 + 名称」，而 ToolSource 还带 {@code authHeaderCipher}（密文）、
 * 掩码与出站地址。缓存整个实体等于把凭据密文写进 Redis，违反密钥边界；
 * 用类型级投影把「不该进缓存」的字段直接排除掉，比靠调用方自觉更可靠。
 * <p>
 * 只读：本类只由仓储读出后投影，不回写库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolSourceView {

    private Long id;
    private String name;
    /** BUILTIN / MCP；装配按它决定走内置 schema 还是 MCP 句柄 */
    private String type;
    /** 1 可用，0 停用；停用等价整源下线 */
    private Integer status;

    /**
     * 从仓储实体投影，密文与掩码不参与。
     *
     * @param source 未删除的工具源
     * @return 只含装配所需字段的投影
     */
    public static ToolSourceView of(ToolSource source) {
        if (source == null) {
            return null;
        }
        return new ToolSourceView(source.getId(), source.getName(), source.getType(), source.getStatus());
    }
}
