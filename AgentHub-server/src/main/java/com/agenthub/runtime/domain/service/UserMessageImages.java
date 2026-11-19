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
        String session = sharedImageSession(urls);
        StringBuilder text = new StringBuilder(PREFIX).append(urls.size()).append(':');
        if (session != null) {
            // 同一会话的图片只留短文件名，9 张才放得进摘要列
            text.append(session).append(':');
        }
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) {
                text.append('|');
            }
            String url = urls.get(i) == null ? "" : urls.get(i).trim();
            if (url.isEmpty() || !allowed(url)) {
                throw new TrialFailure("图片地址无效");
            }
            text.append(session == null ? url : storedImageName(url));
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
        String session = null;
        int sessionColon = rest.indexOf(':');
        if (sessionColon > 0 && rest.substring(0, sessionColon).chars().allMatch(Character::isDigit)) {
            session = rest.substring(0, sessionColon);
            rest = rest.substring(sessionColon + 1);
        }
        String[] parts = rest.split("\\|", -1);
        if (parts.length != count) {
            return List.of();
        }
        List<String> urls = new ArrayList<>(parts.length);
        for (String part : parts) {
            String url = expandImage(part, session);
            if (!allowed(url)) {
                return List.of();
            }
            urls.add(url);
        }
        return List.copyOf(urls);
    }

    /**
     * 同一会话才压缩。外链或跨会话保持完整地址。
     *
     * @param urls 图片地址
     * @return 会话号；不能压缩时为 null
     */
    private static String sharedImageSession(List<String> urls) {
        String session = null;
        for (String raw : urls) {
            String url = raw == null ? "" : raw.trim();
            String prefix = "/uploads/trial-images/";
            if (!url.startsWith(prefix)) {
                return null;
            }
            String rest = url.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String found = rest.substring(0, slash);
            if (found.isEmpty() || !found.chars().allMatch(Character::isDigit)) {
                return null;
            }
            if (session == null) {
                session = found;
            } else if (!session.equals(found)) {
                return null;
            }
        }
        return session;
    }

    /**
     * @param url 落盘地址
     * @return 最后一段
     */
    private static String storedImageName(String url) {
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            throw new TrialFailure("图片地址无效");
        }
        return url.substring(slash + 1);
    }

    /**
     * @param part    短文件名或完整地址
     * @param session 会话号，旧清单为 null
     * @return 可放进 img 的地址
     */
    private static String expandImage(String part, String session) {
        if (part.startsWith("/") || part.startsWith("http://") || part.startsWith("https://")) {
            return part;
        }
        if (session == null || part.isBlank() || part.indexOf('/') >= 0) {
            return part;
        }
        return "/uploads/trial-images/" + session + "/" + part;
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
