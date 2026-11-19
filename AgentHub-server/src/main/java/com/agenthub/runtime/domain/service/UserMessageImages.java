package com.agenthub.runtime.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户消息附图的落库清单。所属域：runtime。
 *
 * <p>正文仍写在 {@code content_text}（纯文本，与历史事件兼容）。
 * 图片地址写在 {@code summary}，前缀 {@code ahimg:}，刷新回放和内存快照都读这一份，
 * 不另开补查接口。无图时 summary 仍是正文短摘要，行为与原来一致。
 *
 * <p>记忆窗口和续作问句必须先走 {@link #questionText}：清单不是用户原话。
 */
public final class UserMessageImages {

    /** 只发图、没有文字时，左栏预览和标题用的短标签。不写进气泡正文。 */
    public static final String IMAGE_ONLY_LABEL = "［图片］";

    /** summary 前缀。后面是张数和以 | 分隔的地址。 */
    static final String PREFIX = "ahimg:";

    /** 摘要列 500 字，清单必须留在截断线以内，否则回放对不上张数会整单作废。 */
    private static final int MANIFEST_LIMIT = 480;

    private UserMessageImages() {
    }

    /**
     * 把已落盘的图片地址收成一条摘要。无图返回 null，调用方继续用正文做摘要。
     *
     * @param urls 按发送顺序的地址，可空
     * @return {@code ahimg:张数:地址|地址}；无图时 null
     * @throws TrialFailure 拼出来超过摘要安全长度
     */
    public static String manifest(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder(PREFIX).append(urls.size()).append(':');
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) {
                text.append('|');
            }
            String url = urls.get(i) == null ? "" : urls.get(i).trim();
            if (url.isEmpty()) {
                throw new TrialFailure("图片地址无效");
            }
            text.append(url);
        }
        String value = text.toString();
        if (value.length() > MANIFEST_LIMIT) {
            throw new TrialFailure("图片地址过长，请改为上传图片文件");
        }
        return value;
    }

    /**
     * 从摘要里取出图片地址。不是清单、张数对不上、或地址不在允许前缀内时返回空列表，
     * 避免把普通用户原文误当成图。
     *
     * @param summary 事件摘要，可空
     * @return 按原顺序的地址；认不出时为空列表
     */
    public static List<String> imagesOf(String summary) {
        if (summary == null || !summary.startsWith(PREFIX)) {
            return List.of();
        }
        int countColon = summary.indexOf(':', PREFIX.length());
        if (countColon < 0) {
            return List.of();
        }
        int count;
        try {
            count = Integer.parseInt(summary.substring(PREFIX.length(), countColon));
        } catch (NumberFormatException ex) {
            return List.of();
        }
        if (count < 1 || count > VisionOcrTexts.MAX_IMAGES) {
            return List.of();
        }
        String rest = summary.substring(countColon + 1);
        if (rest.isEmpty()) {
            return List.of();
        }
        String[] parts = rest.split("\\|", -1);
        if (parts.length != count) {
            return List.of();
        }
        List<String> urls = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!allowed(part)) {
                return List.of();
            }
            urls.add(part);
        }
        return List.copyOf(urls);
    }

    /**
     * 取出用户原话。附图清单不能进记忆窗口，也不能当成续作问句。
     *
     * @param contentText 正文，可空
     * @param summary     摘要，可空；有图时是 ahimg 清单，有文件时是 ahfile 清单
     * @return 用户原话；只发图或只发文件时为空串
     */
    public static String questionText(String contentText, String summary) {
        // 文件清单同样不是用户原话。解析失败也要拦住，避免半截 ahfile 灌进记忆窗口。
        boolean illustrated = !imagesOf(summary).isEmpty() || UserMessageFiles.looksLikeManifest(summary);
        if (contentText != null && !contentText.isBlank()) {
            return contentText.trim();
        }
        if (illustrated) {
            return "";
        }
        return summary == null ? "" : summary.trim();
    }

    /**
     * 只接受本服务落盘的相对地址，或调用方原来就给的 http(s) 地址。
     *
     * @param url 单张地址
     * @return 能放进 {@code <img src>} 才为 true
     */
    private static boolean allowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.startsWith("/uploads/trial-images/")
                || url.startsWith("https://")
                || url.startsWith("http://");
    }
}
