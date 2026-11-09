package com.agenthub.auth.domain.model;

import com.agenthub.admin.domain.model.Menu;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 某个平台用户的访问快照。所属域：auth。
 * <p>
 * 一份快照同时承载「角色编码 + 权限码集合 + 导航菜单树」，三者同源同版本：
 * 分开缓存会出现「权限已是新角色、菜单还是旧角色」的中间态，而 /me 与注解守卫会各自读到不一致的结果。
 * <p>
 * 字段口径与改造前的三次独立计算完全一致：
 * <ul>
 *   <li>{@code roles}：启用角色的编码，去重去空；<b>不看用户启停</b>（旧 {@code roleCodes} 即如此）；</li>
 *   <li>{@code permissions}：Sa-Token 注解校验的那份；用户停用时为空；</li>
 *   <li>{@code menus}：已组树、已剔除按钮的导航树（旧 {@code me} 里 {@code buildNavigable} 的结果）。</li>
 * </ul>
 * <p>
 * {@code userId} 是**防串值校验位**：反序列化后必须与请求用户一致，否则视为坏值回库，
 * 避免「其它用户的权限码串进本次结果」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessSnapshot {

    /** 快照归属用户；缓存反序列化后用它校验，不用于业务判断 */
    private Long userId;
    /** 启用角色编码（去重去空）；停用用户仍返回其角色，保持旧行为 */
    private List<String> roles = new ArrayList<>();
    /** 权限码集合，供 {@code StpInterface.getPermissionList} 与 /me 使用 */
    private List<String> permissions = new ArrayList<>();
    /** 导航菜单树：已按权限过滤、已剔除按钮、已按 sort/id 排好 */
    @ToString.Exclude
    private List<Menu> menus = new ArrayList<>();

    /**
     * 空快照：用户为空或数据不可用时使用。
     * 注意：**空权限等于 403**，因此「缓存坏值」绝不能落到这里，必须回库重读。
     *
     * @return 角色/权限/菜单都为空的快照
     */
    public static AccessSnapshot empty() {
        return new AccessSnapshot(null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }
}
