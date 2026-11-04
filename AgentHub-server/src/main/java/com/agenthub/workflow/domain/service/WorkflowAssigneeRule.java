package com.agenthub.workflow.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HUMAN 节点处理人规则（W4）。所属域：workflow 的 domain。
 *
 * <p>语法只有两种（空规则兼容刀2）：
 * <ul>
 *   <li>{@code ROLE:<roleCode>}：当前用户启用角色里命中该角色码即可处理；</li>
 *   <li>{@code USER:<id>[,<id>...]}：当前用户 id 命中列表任一即可处理；</li>
 *   <li>空 / 空串：只要具备 {@code workflow:human:act} 且同租户即可处理（历史图兼容）。</li>
 * </ul>
 *
 * <p>为什么集中在一个纯函数里：图校验（发布前拦非法规则）、待办列表（mine 过滤）、
 * approve/reject 鉴权三处必须同口径，否则会出现「列表能看见但批不了」的分裂。
 *
 * <p>安全边界：不支持通配符（如 {@code USER:*} / {@code ROLE:*}），也不支持逗号混写
 * USER 与 ROLE；解析失败一律视为非法规则，由校验器拒发、由鉴权按无权限处理。
 * 不使用 SpEL / 正则拼接，避免注入。
 */
public final class WorkflowAssigneeRule {

    /** 规则种类 */
    public enum Kind {
        /** 空规则：有 human:act 权限即可 */
        EMPTY,
        /** 指定用户名单 */
        USER,
        /** 指定角色码 */
        ROLE
    }

    /** 角色码口径：与 sys_role.code 对齐，允许字母数字与 _ . : - */
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    /** 单条规则最多列多少个用户 id，防止脏数据把鉴权做成 O(n) 扫描 */
    public static final int MAX_USER_IDS = 100;

    /** 规则原文最大长度（与 workflow_human_task.assignee_rule VARCHAR(128) 对齐） */
    public static final int MAX_RULE_LENGTH = 128;

    private WorkflowAssigneeRule() {
    }

    /**
     * 解析规则。
     *
     * @param raw 规则原文，可空
     * @return 解析结果；非法时 {@link Parsed#problem()} 为中文原因，其余字段尽力填充
     */
    public static Parsed parse(String raw) {
        String text = normalize(raw);
        if (text == null) {
            return new Parsed(Kind.EMPTY, null, List.of(), null);
        }
        if (text.length() > MAX_RULE_LENGTH) {
            return new Parsed(Kind.EMPTY, null, List.of(),
                    "处理人规则长度 " + text.length() + " 超过上限 " + MAX_RULE_LENGTH + " 字符");
        }
        String upper = text.toUpperCase();
        if (upper.startsWith("USER:")) {
            return parseUsers(text.substring(5));
        }
        if (upper.startsWith("ROLE:")) {
            return parseRole(text.substring(5));
        }
        return new Parsed(Kind.EMPTY, null, List.of(),
                "处理人规则只能写 ROLE:角色码 或 USER:用户id,用户id；当前为「" + text + "」");
    }

    /**
     * 判断当前用户能否处理。
     *
     * <p>空规则恒 true（调用方已用权限注解保证 human:act）。
     *
     * @param raw        规则原文
     * @param operatorId 当前用户 id
     * @param roleCodes  当前用户启用角色码，可空
     * @return true 表示可处理；规则非法返回 false（由调用方给中文错误）
     */
    public static boolean matches(String raw, long operatorId, List<String> roleCodes) {
        Parsed parsed = parse(raw);
        if (parsed.problem() != null) {
            return false;
        }
        return switch (parsed.kind()) {
            case EMPTY -> true;
            case USER -> parsed.userIds().contains(operatorId);
            case ROLE -> roleCodes != null && roleCodes.stream()
                    .anyMatch(code -> code != null && code.equalsIgnoreCase(parsed.roleCode()));
        };
    }

    /**
     * 规则归一：去首尾空白，空串视为「无规则」。
     *
     * @param raw 原文
     * @return 归一后的文本；空返回 null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 规则的中文展示（待办列表 / 试跑抽屉用）。
     *
     * @param raw 规则原文
     * @return 中文描述；空规则返回「任何人（需处理权限）」
     */
    public static String describe(String raw) {
        Parsed parsed = parse(raw);
        if (parsed.problem() != null) {
            return "规则非法：" + parsed.problem();
        }
        return switch (parsed.kind()) {
            case EMPTY -> "任何人（需处理权限）";
            case USER -> "指定用户：" + parsed.userIds().stream().map(String::valueOf)
                    .reduce((left, right) -> left + "," + right).orElse("");
            case ROLE -> "角色：" + parsed.roleCode();
        };
    }

    private static Parsed parseUsers(String body) {
        List<Long> ids = new ArrayList<>();
        for (String part : body.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(token);
                if (id <= 0) {
                    return new Parsed(Kind.USER, null, List.of(),
                            "处理人规则里的用户 id 必须是正整数，当前为「" + token + "」");
                }
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ex) {
                return new Parsed(Kind.USER, null, List.of(),
                        "处理人规则里的用户 id 不是数字：「" + token + "」");
            }
        }
        if (ids.isEmpty()) {
            return new Parsed(Kind.USER, null, List.of(),
                    "处理人规则 USER: 后至少要写 1 个用户 id");
        }
        if (ids.size() > MAX_USER_IDS) {
            return new Parsed(Kind.USER, null, List.of(),
                    "处理人规则最多列出 " + MAX_USER_IDS + " 个用户，当前 " + ids.size() + " 个");
        }
        return new Parsed(Kind.USER, null, List.copyOf(ids), null);
    }

    private static Parsed parseRole(String body) {
        String code = body.trim();
        if (code.isEmpty()) {
            return new Parsed(Kind.ROLE, null, List.of(),
                    "处理人规则 ROLE: 后必须写角色码");
        }
        if (!ROLE_CODE.matcher(code).matches()) {
            return new Parsed(Kind.ROLE, null, List.of(),
                    "角色码「" + code + "」不合法：只允许字母、数字、下划线、点、冒号、连字符，最长 64 字符");
        }
        return new Parsed(Kind.ROLE, code, List.of(), null);
    }

    /**
     * 解析结果。
     *
     * @param kind     规则种类
     * @param roleCode ROLE 规则的角色码
     * @param userIds  USER 规则的用户 id（去重）
     * @param problem  非法原因；合法为 null
     */
    public record Parsed(Kind kind, String roleCode, List<Long> userIds, String problem) {
    }
}
