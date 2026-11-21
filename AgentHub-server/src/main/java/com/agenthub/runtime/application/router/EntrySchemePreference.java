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
        if (nameHits(userText, duty)) {
            return true;
        }
        String name = duty.name() == null ? "" : duty.name().trim();
        return descriptionHits(userText, duty.description(), name) >= 1;
    }

    /**
     * 名称三档命中：全名 → 核心名（去称谓）→ 核心名首尾两字（核心名至少四字时）。所属域：runtime。
     *
     * <p>入口与方案内层共用这一份口径：入口拿它数「一个方案有几名成员被打中」，
     * 内层拿它数「当前方案有几名候选被打中」。拆出来只为让内层能在此基础上收紧，
     * 入口 {@link #memberHits} 的判定结果与拆之前逐字一致。
     *
     * @param userText 用户原问
     * @param duty     成员名称与简介（简介在这里不参与）
     * @return true 表示名称的前三档有一档命中
     */
    static boolean nameHits(String userText, MemberDuty duty) {
        String haystack = userText.toLowerCase(Locale.ROOT);
        String name = duty.name() == null ? "" : duty.name().trim();
        if (name.length() >= 2 && haystack.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String core = nameCore(name);
        if (core.length() >= 2 && haystack.contains(core.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return edgeHits(haystack, core);
    }

    /**
     * 核心名首尾两字是否出现在问句里（核心名至少四字才做这一档）。所属域：runtime。
     *
     * @param haystack 已转小写的问句
     * @param core     已去称谓的成员核心名；空按不命中
     * @return true 表示首两字或尾两字被问句包含
     */
    static boolean edgeHits(String haystack, String core) {
        return edgeIndex(haystack, core) >= 0;
    }

    /**
     * 核心名首尾两字在问句里的起始下标（首两字优先，其次尾两字）。所属域：runtime。
     *
     * <p>方案内层的相邻收紧要按位置判断「这两个字是不是和另一位成员的用字紧挨着」，
     * 所以这里返回下标而不是布尔。入口 {@link #edgeHits} 仍只消费它的非负性。
     *
     * @param haystack 已转小写的问句
     * @param core     已去称谓的成员核心名；不足四字按不命中
     * @return 命中起始下标；不命中为 -1
     */
    static int edgeIndex(String haystack, String core) {
        if (haystack == null || core == null || core.length() < 4) {
            return -1;
        }
        String head = core.substring(0, 2).toLowerCase(Locale.ROOT);
        int at = haystack.indexOf(head);
        if (at >= 0) {
            return at;
        }
        String tail = core.substring(core.length() - 2).toLowerCase(Locale.ROOT);
        return haystack.indexOf(tail);
    }

    /**
     * 方案内层的职责覆盖：这一问打中了哪几名候选。所属域：runtime。
     *
     * <p>口径与入口 {@link #memberHits} 同源（全名 / 核心名 / 首尾两字 / 简介短句四档），
     * 唯一差别是<b>收紧首尾两字档</b>：若只靠这一档命中的两段在问句里紧挨着
     * （如「退款制度」里的「退款」与「制度」），只保留后一段——中文复合词的中心语在末尾，
     * 前一段只是修饰，不该让「退款客服专家」把一整个制度类问题算成自己的一块交付。
     * 全名、核心名、简介短句三档与入口逐字一致，不受收紧影响。
     *
     * <p>只对传进来的这一组成员做判定，不查库、不看方案主键；调用方按候选顺序给入，
     * 返回的布尔数组与之同序，命中几位就是几位。
     *
     * @param userText 用户原问
     * @param duties   当前方案的启用成员（名称 + 简介），按候选顺序
     * @return 与 {@code duties} 同序的命中标记；入参为空时返回空数组
     */
    public static boolean[] coveredBy(String userText, List<MemberDuty> duties) {
        if (duties == null || duties.isEmpty()) {
            return new boolean[0];
        }
        boolean[] covered = new boolean[duties.size()];
        // 只记录「仅靠首尾两字命中」的位置；强命中（全名 / 核心名 / 简介）留 -1，不参与相邻收紧
        int[] edgeAt = new int[duties.size()];
        for (int i = 0; i < edgeAt.length; i++) {
            edgeAt[i] = -1;
        }
        if (userText == null || userText.isBlank()) {
            return covered;
        }
        String haystack = userText.toLowerCase(Locale.ROOT);
        for (int i = 0; i < duties.size(); i++) {
            MemberDuty duty = duties.get(i);
            if (duty == null) {
                continue;
            }
            String name = duty.name() == null ? "" : duty.name().trim();
            String core = nameCore(name);
            // 全名 / 核心名：入口原样，命中即算，不再看后面几档
            if ((name.length() >= 2 && haystack.contains(name.toLowerCase(Locale.ROOT)))
                    || (core.length() >= 2 && haystack.contains(core.toLowerCase(Locale.ROOT)))) {
                covered[i] = true;
                continue;
            }
            // 简介短句：同样原样保留，属于比首尾两字更具体的证据
            if (descriptionHits(userText, duty.description(), name) >= 1) {
                covered[i] = true;
                continue;
            }
            edgeAt[i] = edgeIndex(haystack, core);
            covered[i] = edgeAt[i] >= 0;
        }
        /*
         * 相邻收紧：仅靠首尾两字命中的两段若紧挨着（a 结束处正是 b 开始处），
         * 前一段是修饰、后一段才是中心语，只留后一段。
         * 例：「查询下退款制度」里「退款」(退款客服专家) 与「制度」(制度知识专家) 相邻，
         * 只算制度知识专家。
         */
        for (int i = 0; i < duties.size(); i++) {
            if (!covered[i] || edgeAt[i] < 0) {
                continue;
            }
            for (int j = 0; j < duties.size(); j++) {
                if (i == j || edgeAt[j] < 0) {
                    continue;
                }
                if (edgeAt[i] + 2 == edgeAt[j]) {
                    covered[i] = false;
                    break;
                }
            }
        }
        return covered;
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
