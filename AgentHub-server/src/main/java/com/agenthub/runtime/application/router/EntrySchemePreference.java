package com.agenthub.runtime.application.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工作台入口的选人纠偏。所属域：runtime。
 *
 * <p>只在入口 {@code ENTRY_ROUTER} 的既有判路由结果上收口，不另起路由引擎，
 * 也不改 {@code preferSchemes}：成员对不上的裸专家仍留在池里，但点名方案、
 * 或多交付已被某方案覆盖时，不再把整句截给其中一个单兵。
 * 单项问题、闲聊不在这里改派。{@code @} / sticky 由调用方先处理，本类不看。
 */
public final class EntrySchemePreference {

    /** 点名方案时写进路由依据的一句人话，不暴露 route_key。 */
    public static final String NAMED_REASON = "用户点名了该方案，由方案再分发给对应专家";

    /** 多项交付改派给方案时的一句人话。 */
    public static final String MULTI_REASON = "问句包含多项交付且该方案已覆盖，交给方案再分发，不由单个专家截走";

    private static final String[] NAME_SUFFIXES = {"专家", "助手", "机器人"};

    private EntrySchemePreference() {
    }

    /**
     * 问句里点名了池中某个已启用方案时，返回该方案。
     *
     * <p>多个名称同时出现时取得最长的那个，避免短名先把长名截断。
     * 只认方案候选，裸专家名称不算点名方案。
     *
     * @param userText   用户原问；可空
     * @param candidates 入口本轮可用候选
     * @return 被点名的方案；没有则 null
     */
    public static Candidate namedScheme(String userText, List<Candidate> candidates) {
        if (userText == null || userText.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        String haystack = userText.toLowerCase(Locale.ROOT);
        Candidate best = null;
        int bestLength = 1;
        for (Candidate candidate : candidates) {
            if (!isScheme(candidate)) {
                continue;
            }
            String name = displayName(candidate);
            if (name.length() < 2) {
                continue;
            }
            if (!haystack.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (name.length() > bestLength) {
                best = candidate;
                bestLength = name.length();
            }
        }
        return best;
    }

    /**
     * 问句同时打中同一方案的至少两名成员，或简介里至少两段短句时，返回该方案。
     *
     * <p>只打中一名成员（单项制度 / 单项报告）返回 null，让原来的专家选择留下。
     * 成员资料由调用方按方案主键给入，本类不查库。
     *
     * @param userText       用户原问；可空
     * @param candidates     入口本轮可用候选
     * @param dutiesByScheme 方案主键 → 启用中的成员名称与简介；可空
     * @return 应接管整句的方案；没有则 null
     */
    public static Candidate coveringScheme(String userText, List<Candidate> candidates,
                                           Map<Long, List<MemberDuty>> dutiesByScheme) {
        if (userText == null || userText.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Candidate best = null;
        int bestRank = 0;
        for (Candidate candidate : candidates) {
            if (!isScheme(candidate)) {
                continue;
            }
            Long schemeId = candidate.maTarget().maId();
            List<MemberDuty> duties = dutiesByScheme == null || schemeId == null
                    ? List.of()
                    : dutiesByScheme.getOrDefault(schemeId, List.of());
            int members = 0;
            for (MemberDuty duty : duties) {
                if (duty != null && memberHits(userText, duty)) {
                    members++;
                }
            }
            int clauses = descriptionHits(userText, candidate.maTarget().description(), displayName(candidate));
            if (members < 2 && clauses < 2) {
                continue;
            }
            int rank = members * 10 + clauses;
            if (rank > bestRank) {
                best = candidate;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * 一名成员是否被问句打中：全名、去掉「专家 / 助手」后的核心名，
     * 或核心名首尾两字（至少四字时），或简介里能整段出现在问句中的短句。
     *
     * @param userText 用户原问
     * @param duty     成员名称与简介
     * @return true 表示这一名成员覆盖了问句里的一块交付
     */
    static boolean memberHits(String userText, MemberDuty duty) {
        String haystack = userText.toLowerCase(Locale.ROOT);
        String name = duty.name() == null ? "" : duty.name().trim();
        if (name.length() >= 2 && haystack.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String core = nameCore(name);
        if (core.length() >= 2 && haystack.contains(core.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (core.length() >= 4) {
            String head = core.substring(0, 2);
            String tail = core.substring(core.length() - 2);
            if (haystack.contains(head.toLowerCase(Locale.ROOT))
                    || haystack.contains(tail.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return descriptionHits(userText, duty.description(), name) >= 1;
    }

    /**
     * 简介里有几段 2～12 字的短句整段出现在问句中。方案名本身不算一段，避免和点名规则重复计数。
     */
    private static int descriptionHits(String userText, String description, String schemeName) {
        if (description == null || description.isBlank()) {
            return 0;
        }
        String haystack = userText.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String clause : shortClauses(description)) {
            if (schemeName != null && clause.equals(schemeName.trim())) {
                continue;
            }
            if (haystack.contains(clause.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    /** 按中英文标点切开，只保留能当「一块交付」的短句。 */
    private static List<String> shortClauses(String text) {
        String[] parts = text.split("[，。；、,.;：:\\n\\r/|]+");
        List<String> clauses = new ArrayList<>();
        for (String part : parts) {
            String clause = part == null ? "" : part.trim();
            if (clause.length() >= 2 && clause.length() <= 12) {
                clauses.add(clause);
            }
        }
        return clauses;
    }

    /** 去掉末尾的通用称谓，留下「制度知识」「报告撰写」这种职责核心。 */
    private static String nameCore(String name) {
        String text = name == null ? "" : name.trim();
        for (String suffix : NAME_SUFFIXES) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                return text.substring(0, text.length() - suffix.length()).trim();
            }
        }
        return text;
    }

    private static boolean isScheme(Candidate candidate) {
        return candidate != null && candidate.isMaTarget() && candidate.maTarget() != null;
    }

    private static String displayName(Candidate candidate) {
        String name = candidate.maTarget().name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String key = candidate.route().getRouteKey();
        return key == null ? "" : key.trim();
    }

    /**
     * 方案一名启用成员的名称和简介。所属域：runtime。
     *
     * @param name        成员展示名
     * @param description 成员简介；可空
     */
    public record MemberDuty(String name, String description) {
    }
}
