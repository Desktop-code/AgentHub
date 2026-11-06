package com.agenthub.model.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型实例的「附加参数」。所属域：model。
 *
 * <p>存放位置：{@code model_instance.extra_json} 的 {@code speechParams} 子对象
 * （与 {@code temperature} / {@code max_tokens} 同级）。不新建列、不改音色/语速的既有语义。
 *
 * <p>语义：用户在界面上按厂家文档填写的一段 JSON 对象，出站时<b>浅合并</b>进该协议
 * TTS 请求的「可选参数位置」；平台<b>不做</b>键名转换 / 映射 / 翻译，厂家不认的键由对方报错。
 *
 * <p>安全边界（写库与出站两层都要守住）：
 * <ul>
 *   <li>根节点必须是 JSON 对象，数组 / 字符串 / 数字一律拒绝；</li>
 *   <li>体积上限 {@link #MAX_BYTES}；</li>
 *   <li>拒绝控制字符（NUL、退格等），只放过 \t \n \r；</li>
 *   <li>拒绝凭证类键（api_key / Authorization / token / secret …），凭证只由平台注入；</li>
 *   <li>拒绝系统保留键（model / input / file / text），文本与模型名以实例配置为准。</li>
 * </ul>
 *
 * <p>线程：无状态静态纯函数，可并发调用。
 */
public final class SpeechParams {

    /** extraJson 内存放附加参数对象的键名；前端同名字段写入 */
    public static final String KEY = "speechParams";

    /** 附加参数序列化后的体积上限（字节，UTF-8） */
    public static final int MAX_BYTES = 8 * 1024;

    /**
     * 系统保留键：一律以平台拼装的值为准，用户 JSON 既不能写也不能覆盖。
     * {@code text} 在 DashScope 原生口是待朗读文字，与 {@code input} 同性质。
     */
    private static final Set<String> RESERVED_KEYS = Set.of("model", "input", "file", "text");

    /**
     * 凭证类键：比较前会去掉 - _ 与空格并转小写，因此 api_key / apiKey / X-Api-Key 都能命中。
     */
    private static final Set<String> CREDENTIAL_KEYS = Set.of(
            "apikey", "xapikey", "authorization", "auth", "token", "accesstoken", "refreshtoken",
            "secret", "clientsecret", "appsecret", "accesskey", "accesskeyid", "accesskeysecret",
            "password", "passwd", "credential", "credentials", "bearer");

    private static final ObjectMapper JSON = new ObjectMapper();

    private SpeechParams() {
    }

    /**
     * 校验 extra_json 里的附加参数。空、缺键都合法；非法即抛中文业务异常，阻断保存。
     *
     * @param extraJson 已通过 JSON 语法校验的 extra_json 文本，可空
     * @throws BizException 根节点不是对象、体积超限、含控制字符或含凭证 / 保留键
     */
    public static void validate(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = JSON.readTree(extraJson);
        } catch (Exception ex) {
            // 语法错误由 ModelChecks 先报，这里保持静默兜底，不重复提示
            return;
        }
        JsonNode node = root == null ? null : root.get(KEY);
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "附加参数必须是 JSON 对象，例如 {\"voice\":\"longanhuan_v3.1\"}，不支持数组或字符串");
        }
        int bytes = node.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "附加参数最长 " + MAX_BYTES / 1024 + "KB，当前 " + bytes + " 字节");
        }
        guardKeysAndText(node);
    }

    /**
     * 读取附加参数（出站用）。非对象、坏 JSON 一律按空处理，并再过滤一次凭证 / 保留键。
     *
     * @param extraJson 实例 extra_json，可空或非法
     * @return 保持原键名的浅层参数；无配置时为空 Map
     */
    public static Map<String, Object> parse(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = JSON.readTree(extraJson).get(KEY);
        } catch (Exception ex) {
            // 库里坏值按「未配置」处理：附加参数不该把语音出站整体拖垮
            return Map.of();
        }
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (isBlockedKey(entry.getKey())) {
                // 出站第二道闸：历史脏数据里就算写了凭证 / 保留键，也不许覆盖系统值
                continue;
            }
            out.put(entry.getKey(), JSON.convertValue(entry.getValue(), Object.class));
        }
        return out;
    }

    /**
     * 把附加参数浅合并进目标报文。只加不改系统保留键；同名可选键由用户值覆盖
     * （例如 voice 覆盖平台默认音色，这正是「按厂家文档填写」的预期）。
     *
     * @param target 平台已拼好的请求体 / 可选参数段，按调用方引用直接写入
     * @param params {@link #parse(String)} 的结果，可空
     */
    public static void mergeInto(Map<String, Object> target, Map<String, Object> params) {
        if (target == null || params == null || params.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (isBlockedKey(entry.getKey())) {
                continue;
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * @param key 用户写的键名
     * @return true 表示凭证类或系统保留键，禁止写入 / 出站
     */
    public static boolean isBlockedKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return RESERVED_KEYS.contains(normalized) || CREDENTIAL_KEYS.contains(normalized);
    }

    /**
     * @param key 用户写的键名
     * @return true 表示凭证类键（用于保存时给更准确的提示）
     */
    public static boolean isCredentialKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return CREDENTIAL_KEYS.contains(normalized);
    }

    /**
     * @param key 用户写的键名
     * @return true 表示系统保留键
     */
    public static boolean isReservedKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return RESERVED_KEYS.contains(normalized);
    }

    /**
     * 递归检查键名与文本值：键命中黑名单、文本含控制字符都直接拒绝保存。
     * 提示只带键名，不回显用户内容，避免把疑似密钥写进错误信息。
     *
     * @param node 附加参数子树
     */
    private static void guardKeysAndText(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (isCredentialKey(key)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "附加参数不能包含凭证类键「" + key + "」，凭证由平台按实例注入");
                }
                if (isReservedKey(key)) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "附加参数不能包含系统保留键「" + key + "」，模型名与待朗读文本以实例配置为准");
                }
                guardKeysAndText(entry.getValue());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                guardKeysAndText(item);
            }
            return;
        }
        if (node.isTextual() && hasControlChar(node.asText())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附加参数含非法控制字符，请检查后重填");
        }
    }

    /**
     * @param text 文本值
     * @return true 表示含 \t \n \r 之外的控制字符
     */
    private static boolean hasControlChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                return true;
            }
            if (ch == 0x7F) {
                return true;
            }
        }
        return false;
    }
}
