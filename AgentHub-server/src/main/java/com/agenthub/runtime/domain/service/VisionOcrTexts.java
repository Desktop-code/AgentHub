package com.agenthub.runtime.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片文字提取（OCR）的固定文案、边界与拼接规则。所属域：runtime（domain service）。
 *
 * <p>为什么单独成类而不散落在执行器里：
 * <ol>
 *   <li>提示词、汇总格式、拼接格式是<b>对模型与前端双边的契约</b>，散落会导致三处各写一遍、互不一致；</li>
 *   <li>这些方法都是纯函数（无 IO、无状态），可以脱离模型出站单独单测；</li>
 *   <li>数量与体积上限属于「出站边界」，必须与调用方（AppService）共用同一份常量，
 *       否则校验放过了、执行器又拒，用户会看到自相矛盾的错误。</li>
 * </ol>
 *
 * 线程安全：全部为静态纯函数，常量不可变。
 */
public final class VisionOcrTexts {

    /**
     * 视觉模型的系统提示词。要求「只输出文字」是刻意的：
     * 下游是对话模型，任何解释性客套都会污染它的上下文，也会让用户误以为是答案的一部分。
     */
    public static final String OCR_SYSTEM_PROMPT =
            "请提取这张图片中的所有文字内容，直接输出文字，不要任何多余解释。";

    /**
     * 单张图的用户侧提示。系统提示已说明任务，这里只做最小指代，
     * 避免某些视觉模型把两段相近的中文指令当成两次任务而重复输出。
     */
    public static final String OCR_USER_PROMPT = "这张图片：";

    /** 单轮最多处理的图片数。超过即拒绝：图片是 token 大户，无上限会让一次试跑直接烧掉配额 */
    public static final int MAX_IMAGES = 4;

    /** 单张图片字符串（URL 或 base64 data URI）的最大字符数。约等于 6MB 原图 */
    public static final int MAX_IMAGE_CHARS = 8 * 1024 * 1024;

    /** 拼接后的 OCR 文本上限。超出会挤掉用户真正的问题，按字符截断并注明 */
    public static final int MAX_OCR_CHARS = 16 * 1024;

    /** 单张图识别失败时的占位。保留序号，用户能看出是第几张没识别出来 */
    public static final String FAILED_MARK = "（本图识别失败）";

    /** 与 {@code ContextWindowFacade.renderUser} 的落点一致，用于定位「当前用户」段 */
    static final String CURRENT_USER_MARK = "【当前用户】\n";

    private VisionOcrTexts() {
    }

    /**
     * 把多张图的识别结果汇总成一段文本，格式为 {@code [图1内容]: xxx\n[图2内容]: yyy}。
     *
     * <p>为什么带序号：多图时模型需要能把「提取到的文字」与「用户说的第几张图」对上；
     * 不带序号会退化成一段无法定位的流水账。
     *
     * @param perImage 按上传顺序的逐图文本；空元素写成失败占位
     * @return 汇总文本；全部为空时返回空串（调用方据此判断「没提取到任何文字」）
     */
    public static String aggregate(List<String> perImage) {
        if (perImage == null || perImage.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < perImage.size(); i++) {
            String body = perImage.get(i);
            if (body == null || body.isBlank()) {
                body = FAILED_MARK;
            } else {
                body = body.trim();
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("[图").append(i + 1).append("内容]: ").append(body);
        }
        return clip(text.toString());
    }

    /**
     * 把 OCR 结果拼进本轮用户侧文本，得到真正送进对话模型的一版 Query。
     * 形状固定为：
     * <pre>
     * 【图片内容提取结果】:
     * {ocr_text}
     *
     * 【用户提问】:
     * {原 user_query}
     * </pre>
     * 两段标签是给模型的信号：前一段是「素材」，后一段才是「要回答的问题」，
     * 缺少标签时模型容易把提取到的文字当成用户的原话直接复述。
     *
     * @param ocrText   汇总后的识别结果，空表示没有可用结果
     * @param userQuery 原标题（可能已包含会话摘要 / 近轮 / 短期记忆）
     * @return 拼好的文本；ocrText 为空时原样返回 userQuery
     */
    public static String composeQuery(String ocrText, String userQuery) {
        String body = ocrText == null ? "" : ocrText.trim();
        if (body.isEmpty()) {
            return userQuery == null ? "" : userQuery;
        }
        String question = userQuery == null ? "" : userQuery.trim();
        return "【图片内容提取结果】:\n" + body + "\n\n【用户提问】:\n" + question;
    }

    /**
     * 图片列表的边界校验：数量与单张体积。
     * 只做「能不能发」的判断，不判断内容合法性（那属于厂商侧能力）。
     *
     * @param imageUrls 待发送的图片，可空
     * @return 规整后的列表（去空白、去空项）；无有效图片时为空列表
     * @throws TrialFailure 数量或单张体积超限
     */
    public static List<String> normalize(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        List<String> kept = new ArrayList<>();
        for (String url : imageUrls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String value = url.trim();
            if (value.length() > MAX_IMAGE_CHARS) {
                throw new TrialFailure("单张图片过大，请压缩后再上传（单张上限约 6MB）");
            }
            kept.add(value);
        }
        if (kept.size() > MAX_IMAGES) {
            throw new TrialFailure("一次最多上传 " + MAX_IMAGES + " 张图片");
        }
        return kept;
    }

    /**
     * 把 OCR 结果拼进「已注入记忆的用户侧文本」，只替换末尾的当前用户段。
     *
     * <p>为什么不能直接对整段文本做前缀拼接：modelUser 里前面还有【会话摘要】【最近对话】【短期记忆】，
     * 把图片文字插到最前面会让模型误以为素材比历史更靠前，也破坏了既有的段落顺序契约。
     * 因此识别结果一律落在【当前用户】那一段里，与用户本轮的提问相邻。
     *
     * @param modelUser 已拼好摘要/近轮/短期记忆的用户侧文本
     * @param ocrText   汇总后的识别结果，空表示没有可用结果
     * @param userQuery 用户原文，用于识别「当前用户」段；仅在找不到标记时作为兜底
     * @return 替换后的文本；ocrText 为空时原样返回
     */
    public static String applyToModelUser(String modelUser, String ocrText, String userQuery) {
        String body = ocrText == null ? "" : ocrText.trim();
        if (body.isEmpty()) {
            return modelUser;
        }
        String current = modelUser == null ? "" : modelUser;
        // 标记与 ContextWindowFacade.renderUser 的落点保持一致
        int markAt = current.lastIndexOf(CURRENT_USER_MARK);
        if (markAt >= 0) {
            String before = current.substring(0, markAt + CURRENT_USER_MARK.length());
            String original = current.substring(markAt + CURRENT_USER_MARK.length());
            return before + composeQuery(body, original.isBlank() ? userQuery : original);
        }
        // 没有标记（记忆全关或窗口未启用）：此时整段就是本轮用户话，直接替换
        return composeQuery(body, userQuery);
    }

    /**
     * 按字符上限截断，并在末尾说明已被截断。
     *
     * @param text 原文
     * @return 不超过 {@link #MAX_OCR_CHARS} 的文本
     */
    private static String clip(String text) {
        if (text.length() <= MAX_OCR_CHARS) {
            return text;
        }
        String mark = "\n…（图片文字过多，已截断）";
        return text.substring(0, MAX_OCR_CHARS - mark.length()) + mark;
    }
}
