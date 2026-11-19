package com.agenthub.runtime.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户消息里的非图片文件。所属域：runtime。
 *
 * <p>和附图走同一条用户事件：正文仍是原话，附件清单写在 summary。
 * 只有图片时继续用 {@code ahimg:}，这里不改那条口径。
 * 一旦本轮带了文件，摘要改成 {@code ahfile:}，按添加顺序把图和文件列在一起，
 * 刷新和内存快照都读这一份，不再补查。
 *
 * <p>本类只校验类型、体积和清单长度。原文件不整份送进 {@code VisionOcrTexts}；
 * 正文由运行时另读，只有几乎没有可复制文字的 PDF 页才会渲染后走已绑定的视觉模型。
 */
public final class UserMessageFiles {

    /** 只发文件、没有文字时，左栏预览用的短标签。不写进气泡正文。 */
    public static final String FILE_ONLY_LABEL = "［文件］";

    /** summary 前缀。后面是条数，以及按添加顺序排列的图或文件。 */
    public static final String PREFIX = "ahfile:";

    /** 单轮文件个数。与图片张数分开计数，避免互相占坑。 */
    public static final int MAX_FILES = 4;

    /**
     * 解码后单文件上限。对齐 {@code spring.servlet.multipart.max-file-size}（12MB），
     * 超限必须说明，不能悄悄丢掉。
     */
    public static final int MAX_BYTES = 12 * 1024 * 1024;

    /** 摘要列 500 字，清单必须留在截断线以内，否则回放对不上条数会整单作废。 */
    private static final int MANIFEST_LIMIT = 480;

    /** 文件名本身的上限。再长就说清楚，不截成另一个名字。 */
    private static final int MAX_NAME_CHARS = 80;

    /** 预检用的文件名长度：UUID 去掉横线后是 32 位。 */
    private static final int STORED_NAME_CHARS = 32;

    private static final Set<String> ALLOWED = Set.of(
            "pdf", "txt", "csv", "xlsx", "xls", "docx", "doc", "pptx", "ppt", "zip");

    /** 可执行文件明确拒绝，不给新开口。 */
    private static final Set<String> BLOCKED = Set.of("exe", "msi", "bat", "cmd", "ps1", "dll");

    /** 图片扩展名走附图链路，不能当成普通文件绕过视觉校验。 */
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private static final String TYPE_HINT = "pdf、txt、csv、xlsx、xls、docx、doc、pptx、ppt、zip";

    private UserMessageFiles() {
    }

    /**
     * 前端随消息提交的一个文件。所属域：runtime。只含文件名和 data URI，大小以解码结果为准。
     *
     * @param name    原始文件名，可带路径，落盘前会改成最后一段
     * @param dataUrl {@code data:...;base64,...}
     */
    public record NamedPayload(String name, String dataUrl) {
    }

    /**
     * 通过类型校验、尚未落盘的文件。
     *
     * @param name      展示名（不含路径）
     * @param extension 小写扩展名，只可能是允许列表里的值
     * @param dataUrl   原始 data URI
     */
    public record Draft(String name, String extension, String dataUrl) {
    }

    /**
     * 已落盘、可写进摘要的文件。
     *
     * @param name 展示名
     * @param size 解码后的字节数
     * @param url  可打开的地址
     */
    public record Ref(String name, long size, String url) {
    }

    /**
     * 气泡上的一条附件。图和文件按添加顺序混在一起。
     *
     * @param kind image 或 file
     * @param name 文件展示名；图片可空
     * @param size 文件字节数；图片为 0
     * @param url  地址
     */
    public record Item(String kind, String name, long size, String url) {
    }

    /**
     * 校验本轮文件。空列表表示这轮没有文件。
     *
     * @param raw 请求里的文件，可空
     * @return 按传入顺序的草稿
     * @throws TrialFailure 类型不允许、是可执行文件、没有内容，或超过个数
     */
    public static List<Draft> normalize(List<NamedPayload> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Draft> kept = new ArrayList<>();
        for (NamedPayload one : raw) {
            if (one == null) {
                continue;
            }
            String dataUrl = one.dataUrl() == null ? "" : one.dataUrl().trim();
            if (dataUrl.isEmpty()) {
                throw new TrialFailure("文件内容为空");
            }
            if (!dataUrl.regionMatches(true, 0, "data:", 0, 5) || dataUrl.toLowerCase(Locale.ROOT).indexOf(";base64,") < 0) {
                throw new TrialFailure("文件内容无法识别，请重新选择或粘贴");
            }
            String name = basename(one.name());
            if (name.isEmpty()) {
                throw new TrialFailure("文件名无效");
            }
            if (name.length() > MAX_NAME_CHARS) {
                throw new TrialFailure(name + " 文件名过长，请缩短后再发送");
            }
            String ext = extensionOf(name);
            if (BLOCKED.contains(ext)) {
                throw new TrialFailure("不支持发送可执行文件（exe、msi、bat、cmd、ps1、dll）");
            }
            if (IMAGE_EXT.contains(ext)) {
                throw new TrialFailure("图片请随图片一起发送，不要当作普通文件");
            }
            if (!ALLOWED.contains(ext)) {
                throw new TrialFailure("仅支持 " + TYPE_HINT);
            }
            kept.add(new Draft(name, ext, dataUrl));
        }
        if (kept.size() > MAX_FILES) {
            throw new TrialFailure("一次最多上传 " + MAX_FILES + " 个文件");
        }
        return List.copyOf(kept);
    }

    /**
     * 落盘前按本会话真实地址长度预检清单。超长直接拒绝，不写半截文件。
     *
     * @param sessionId 会话主键，用来估算地址长度
     * @param order     添加顺序，元素为 image 或 file；可空则图在前、文件在后
     * @param images    本轮图片张数
     * @param files     已通过类型校验的文件
     * @throws TrialFailure 顺序对不上，或清单放不进摘要列
     */
    public static void assertFits(Long sessionId, List<String> order, int images, List<Draft> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        long safeSession = sessionId == null ? 1L : sessionId;
        List<String> imageUrls = new ArrayList<>();
        for (int i = 0; i < images; i++) {
            imageUrls.add("/uploads/trial-images/" + safeSession + "/" + "0".repeat(STORED_NAME_CHARS) + ".webp");
        }
        List<Ref> refs = new ArrayList<>();
        for (Draft draft : files) {
            String url = "/uploads/trial-files/" + safeSession + "/" + "0".repeat(STORED_NAME_CHARS) + "." + draft.extension();
            refs.add(new Ref(draft.name(), MAX_BYTES, url));
        }
        // 预检和正式清单用同一套拼接；这里用更长的占位地址，过了就一定放得下真实地址
        manifest(order, imageUrls, refs);
    }

    /**
     * 收成一条摘要。无文件时不应调用；纯图片仍走 {@link UserMessageImages#manifest}。
     *
     * @param order     添加顺序，可空
     * @param imageUrls 已落盘的图片地址，顺序与 order 里的 image 一致
     * @param files     已落盘的文件，顺序与 order 里的 file 一致
     * @return {@code ahfile:条数:i,地址|f,文件名,字节,地址}
     * @throws TrialFailure 顺序无效或清单超长
     */
    public static String manifest(List<String> order, List<String> imageUrls, List<Ref> files) {
        List<String> images = imageUrls == null ? List.of() : imageUrls;
        List<Ref> stored = files == null ? List.of() : files;
        List<String> tokens = resolveOrder(order, images.size(), stored.size());
        StringBuilder text = new StringBuilder(PREFIX).append(tokens.size()).append(':');
        int imageCursor = 0;
        int fileCursor = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                text.append('|');
            }
            if ("image".equals(tokens.get(i))) {
                String url = images.get(imageCursor++);
                if (!allowedImage(url)) {
                    throw new TrialFailure("图片地址无效");
                }
                text.append("i,").append(url);
                continue;
            }
            Ref file = stored.get(fileCursor++);
            if (file == null || !allowedFile(file.url()) || file.size() < 0) {
                throw new TrialFailure("文件地址无效");
            }
            text.append("f,").append(escape(file.name())).append(',').append(file.size()).append(',').append(file.url());
        }
        String value = text.toString();
        if (value.length() > MANIFEST_LIMIT) {
            throw new TrialFailure("文件名过长或文件过多，请缩短文件名后再发送");
        }
        return value;
    }

    /**
     * 从摘要还原附件。不是清单、条数对不上或地址不在允许前缀内时返回空列表。
     *
     * @param summary 事件摘要，可空
     * @return 按添加顺序的附件；认不出时为空列表
     */
    public static List<Item> itemsOf(String summary) {
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
        if (count < 1 || count > MAX_FILES + VisionOcrTexts.MAX_IMAGES) {
            return List.of();
        }
        String rest = summary.substring(countColon + 1);
        String[] parts = rest.split("\\|", -1);
        if (parts.length != count) {
            return List.of();
        }
        List<Item> items = new ArrayList<>(parts.length);
        for (String part : parts) {
            Item item = oneItem(part);
            if (item == null) {
                return List.of();
            }
            items.add(item);
        }
        return List.copyOf(items);
    }

    /**
     * 摘要是不是文件清单。解析失败也算，避免把半截清单当成用户原话。
     *
     * @param summary 事件摘要，可空
     * @return 以 {@code ahfile:} 开头则为 true
     */
    public static boolean looksLikeManifest(String summary) {
        return summary != null && summary.startsWith(PREFIX);
    }

    /**
     * 只取最后一段文件名，去掉控制字符。路径不能进展示名。
     *
     * @param raw 原始文件名，可空
     * @return 展示名；空输入返回空串
     */
    static String basename(String raw) {
        String value = raw == null ? "" : raw.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        StringBuilder kept = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 0x20 && ch != 0x7F) {
                kept.append(ch);
            }
        }
        return kept.toString().trim();
    }

    /**
     * @param name 展示名
     * @return 小写扩展名；没有点时为空串
     */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 顺序没传时，图片在前、文件在后，与两条数组各自的顺序一致。
     *
     * @param order  请求里的 image / file
     * @param images 图片张数
     * @param files  文件个数
     * @return 与张数、个数对齐的顺序
     * @throws TrialFailure 出现未知标记，或数量对不上
     */
    private static List<String> resolveOrder(List<String> order, int images, int files) {
        if (order == null || order.isEmpty()) {
            List<String> built = new ArrayList<>(images + files);
            for (int i = 0; i < images; i++) {
                built.add("image");
            }
            for (int i = 0; i < files; i++) {
                built.add("file");
            }
            return built;
        }
        int imageTokens = 0;
        int fileTokens = 0;
        for (String token : order) {
            if ("image".equals(token)) {
                imageTokens++;
            } else if ("file".equals(token)) {
                fileTokens++;
            } else {
                throw new TrialFailure("附件顺序无效");
            }
        }
        if (imageTokens != images || fileTokens != files) {
            throw new TrialFailure("附件顺序无效");
        }
        return order;
    }

    /**
     * 只转义分隔符，中文文件名仍按原字符计数，才能放进 500 字摘要。
     *
     * @param raw 展示名
     * @return 不含竖线和逗号的名字
     */
    private static String escape(String raw) {
        return raw.replace("%", "%25").replace("|", "%7C").replace(",", "%2C");
    }

    /**
     * @param raw 摘要里的文件名
     * @return 还原后的展示名
     */
    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '%' && i + 2 < raw.length()) {
                String hex = raw.substring(i + 1, i + 3);
                if ("25".equals(hex)) {
                    out.append('%');
                    i += 2;
                    continue;
                }
                if ("7C".equalsIgnoreCase(hex)) {
                    out.append('|');
                    i += 2;
                    continue;
                }
                if ("2C".equalsIgnoreCase(hex)) {
                    out.append(',');
                    i += 2;
                    continue;
                }
            }
            out.append(raw.charAt(i));
        }
        return out.toString();
    }

    /**
     * @param part 一条 {@code i,地址} 或 {@code f,名字,字节,地址}
     * @return 认不出时 null，调用方整单作废
     */
    private static Item oneItem(String part) {
        if (part.startsWith("i,")) {
            String url = part.substring(2);
            if (!allowedImage(url)) {
                return null;
            }
            return new Item("image", "", 0L, url);
        }
        if (!part.startsWith("f,")) {
            return null;
        }
        String[] fields = part.split(",", -1);
        if (fields.length != 4) {
            return null;
        }
        long size;
        try {
            size = Long.parseLong(fields[2]);
        } catch (NumberFormatException ex) {
            return null;
        }
        String name = unescape(fields[1]);
        if (name.isBlank() || size < 0 || !allowedFile(fields[3])) {
            return null;
        }
        return new Item("file", name, size, fields[3]);
    }

    /**
     * @param url 图片地址
     * @return 能放进 img 才为 true
     */
    private static boolean allowedImage(String url) {
        if (url == null || url.isBlank() || url.indexOf('|') >= 0 || url.indexOf(',') >= 0) {
            return false;
        }
        return url.startsWith("/uploads/trial-images/")
                || url.startsWith("https://")
                || url.startsWith("http://");
    }

    /**
     * @param url 文件地址
     * @return 能打开或下载才为 true
     */
    private static boolean allowedFile(String url) {
        if (url == null || url.isBlank() || url.indexOf('|') >= 0 || url.indexOf(',') >= 0) {
            return false;
        }
        return url.startsWith("/uploads/trial-files/")
                || url.startsWith("https://")
                || url.startsWith("http://");
    }
}
