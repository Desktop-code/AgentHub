package com.agenthub.runtime.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 一轮编排共享草稿区（键控）。所属域：runtime。
 *
 * <p>生命周期严格绑定「本轮 run」：由 {@code RouterTrialExecutor} 在根执行开始时创建，
 * 根结束（成功 / 失败 / 取消）后调用 {@link #close()} 丢弃；<b>不落库、不进 Redis、不跨 run 复用</b>。
 * 因此这里不需要持久化字段，也不需要多实例一致性。
 *
 * <p>写入纪律（M3 决策：拒绝同键双写，不做 last-write-wins / CRDT）：
 * <ul>
 *   <li>key 必须匹配 {@link #KEY_PATTERN}，长度 ≤ {@link #MAX_KEY_LENGTH}；</li>
 *   <li>value 为字符串，长度 ≤ {@link #MAX_VALUE_LENGTH}，超长直接失败，不静默截断；</li>
 *   <li>板内不同 key 数 ≤ {@link #MAX_KEYS}，第 33 个新键写入失败；</li>
 *   <li>同一个 key 只允许<b>第一次</b>写入成功；后续写入返回 {@link #CODE_KEY_CONFLICT}，原值不变。</li>
 * </ul>
 *
 * <p>可见性：本轮所有参与子（串行后续步、并行同伴）都能读到已写入的 key；
 * 但 JUDGE / AGGREGATE 不会自动把整板塞进提示词，子必须主动调用工具读取。
 *
 * <p>线程安全：并行子会在各自线程里同时 put，写入用 {@code synchronized} 串行化，
 * 保证「键数上限」与「同键只写一次」在竞态下也不被突破；读取走 {@link ConcurrentHashMap}，无锁。
 *
 * <p>防泄漏：本类只做长度上限，不把 value 打进日志（调用方也不得记录 value 全文）。
 */
public final class ScratchBoard {

    /** 写入成功 */
    public static final String CODE_OK = "OK";
    /** key 不存在（读取语义，不报错） */
    public static final String CODE_NOT_FOUND = "SCRATCH_NOT_FOUND";
    /** key 格式非法（空、首字符非字母、含非法字符或超长） */
    public static final String CODE_KEY_INVALID = "SCRATCH_KEY_INVALID";
    /** value 超长；失败而不是截断 */
    public static final String CODE_VALUE_TOO_LONG = "SCRATCH_VALUE_TOO_LONG";
    /** 板内不同 key 数已达上限 */
    public static final String CODE_KEY_LIMIT = "SCRATCH_KEY_LIMIT";
    /** 同键第二次写入被拒绝（M3：不覆盖） */
    public static final String CODE_KEY_CONFLICT = "SCRATCH_KEY_CONFLICT";
    /** 本轮已收尾（根结束 / 取消），不再接受新写 */
    public static final String CODE_CLOSED = "SCRATCH_CLOSED";

    /** key 长度硬顶（正则已含 {0,63}，这里再显式兜一层，便于文案与测试对照） */
    public static final int MAX_KEY_LENGTH = 64;
    /** value 长度硬顶（按字符数计，不含 UTF-8 字节换算） */
    public static final int MAX_VALUE_LENGTH = 4000;
    /** 板内不同 key 数硬顶 */
    public static final int MAX_KEYS = 32;

    /** key 口径：字母开头，后续只允许字母 / 数字 / 下划线 / 点 / 斜杠 / 连字符 */
    public static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_./-]{0,63}$");

    /** key → 条目；值不可变，写入即整体替换（但同键永不替换，故实际只新增） */
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** 被拒绝的同键写入次数，供 obs 只读统计（不记录 value） */
    private final AtomicInteger conflicts = new AtomicInteger();
    /** 根已结束：只读或直接废弃，写入一律失败 */
    private volatile boolean closed;

    /**
     * 读取一个 key。
     *
     * <p>不存在<b>不报错</b>：返回 found=false，让子自己决定退路。
     * key 非法同样不抛异常，用 code 说明原因，避免工具层把参数问题变成整子失败。
     *
     * @param key 草稿键，可空
     * @return 读取结果；found=true 时 value / ownerRouteKey 有值
     */
    public ReadResult get(String key) {
        String normalized = key == null ? "" : key.trim();
        if (!validKey(normalized)) {
            return new ReadResult(false, normalized, null, null, CODE_KEY_INVALID, keyProblem(normalized));
        }
        Entry entry = entries.get(normalized);
        if (entry == null) {
            return new ReadResult(false, normalized, null, null, CODE_NOT_FOUND, "草稿区没有该键");
        }
        return new ReadResult(true, normalized, entry.value(), entry.ownerRouteKey(), CODE_OK, "读取成功");
    }

    /**
     * 写入一个 key（同键只允许首次写入）。
     *
     * <p>校验顺序：板已关闭 → key 合法 → value 长度 → 已有同键 → 键数上限。
     * 「已有同键」优先于「键数上限」：同键冲突是语义错误，键数顶是容量错误，前者更该被看见。
     *
     * @param key           草稿键
     * @param value         要写入的字符串；null 按空串处理
     * @param ownerRouteKey 首写者 route_key / 子名，用于冲突提示与 obs（可空）
     * @return 写入结果；ok=false 时原值保持不变
     */
    public synchronized WriteResult put(String key, String value, String ownerRouteKey) {
        String normalized = key == null ? "" : key.trim();
        if (closed) {
            return new WriteResult(false, normalized, CODE_CLOSED, "本轮编排已结束：共享草稿区不再接受写入", null);
        }
        if (!validKey(normalized)) {
            return new WriteResult(false, normalized, CODE_KEY_INVALID, keyProblem(normalized), null);
        }
        String text = value == null ? "" : value;
        if (text.length() > MAX_VALUE_LENGTH) {
            return new WriteResult(false, normalized, CODE_VALUE_TOO_LONG,
                    "写入失败：value 长度 " + text.length() + " 超过上限 " + MAX_VALUE_LENGTH
                            + " 字符，请精简或拆成多个键", null);
        }
        Entry existing = entries.get(normalized);
        if (existing != null) {
            conflicts.incrementAndGet();
            String owner = existing.ownerRouteKey() == null || existing.ownerRouteKey().isBlank()
                    ? "其他子 Agent" : existing.ownerRouteKey();
            // 回带首写者：让模型知道换 key 还是改读，而不是反复重试同一键
            return new WriteResult(false, normalized, CODE_KEY_CONFLICT,
                    "写入被拒绝：键「" + normalized + "」已被「" + owner
                            + "」首次写入；同键只允许首次写入，请改读该键或换一个 key",
                    existing.ownerRouteKey());
        }
        if (entries.size() >= MAX_KEYS) {
            return new WriteResult(false, normalized, CODE_KEY_LIMIT,
                    "写入失败：本轮草稿区键数已达上限 " + MAX_KEYS + " 个，请复用已有键", null);
        }
        String owner = ownerRouteKey == null ? "" : ownerRouteKey.trim();
        entries.put(normalized, new Entry(normalized, text, owner, Instant.now().toEpochMilli()));
        return new WriteResult(true, normalized, CODE_OK, "写入成功", owner);
    }

    /**
     * @return 当前已写入的不同 key 数（obs 只读统计用）
     */
    public int keyCount() {
        return entries.size();
    }

    /**
     * @return 被拒绝的同键写入次数（obs 只读统计用；不含非法 key / 超长 value）
     */
    public int conflictCount() {
        return conflicts.get();
    }

    /**
     * 根收尾：停止接受新写。已写入内容可继续读（本轮内），下一轮直接丢弃整块板。
     * 重复调用无副作用。
     */
    public void close() {
        closed = true;
    }

    /**
     * @return 是否已停止接受新写
     */
    public boolean closed() {
        return closed;
    }

    /**
     * @param key 已 trim 的键
     * @return 是否符合 key 口径且不超长
     */
    private static boolean validKey(String key) {
        return key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
    }

    /**
     * @param key 非法键
     * @return 中文原因；空键与非空非法键分开说，便于模型改参
     */
    private static String keyProblem(String key) {
        if (key.isEmpty()) {
            return "写入失败：key 不能为空";
        }
        if (key.length() > MAX_KEY_LENGTH) {
            return "写入失败：key 长度 " + key.length() + " 超过上限 " + MAX_KEY_LENGTH;
        }
        return "写入失败：key 只能字母开头，后续允许字母、数字、下划线、点、斜杠、连字符（≤"
                + MAX_KEY_LENGTH + " 字符），当前「" + key + "」不合法";
    }

    /**
     * 一条草稿。value 只存内存，本类不提供任何写日志的入口。
     *
     * @param key           键
     * @param value         正文
     * @param ownerRouteKey 首写者 route_key / 子名
     * @param writtenAt     首写时刻（毫秒）
     */
    public record Entry(String key, String value, String ownerRouteKey, long writtenAt) {
    }

    /**
     * 读取结果。
     *
     * @param found        是否命中
     * @param key          归一后的键
     * @param value        正文；未命中为 null
     * @param ownerRouteKey 首写者；未命中为 null
     * @param code         OK / SCRATCH_NOT_FOUND / SCRATCH_KEY_INVALID
     * @param message      中文说明
     */
    public record ReadResult(boolean found, String key, String value, String ownerRouteKey,
                             String code, String message) {
    }

    /**
     * 写入结果。
     *
     * @param ok           是否写入成功
     * @param key          归一后的键
     * @param code         错误码或 OK
     * @param message      中文说明（可直接给模型）
     * @param ownerRouteKey 冲突时为<b>首写者</b>；成功时为本次写者
     */
    public record WriteResult(boolean ok, String key, String code, String message, String ownerRouteKey) {
    }
}
