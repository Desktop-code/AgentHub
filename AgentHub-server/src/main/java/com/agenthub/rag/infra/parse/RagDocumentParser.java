package com.agenthub.rag.infra.parse;

import com.agenthub.rag.domain.service.RagUploadTypes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 文档解析为纯文本。所属域：rag 的 infra。
 * <p>
 * 默认本地：PDFBox 分页（页间 {@code \f}）/ UTF-8 读 MD·TXT / POI DOCX 标题转 {@code #}。
 * MinerU / Vision 可选：无 Key 不炸；失败按配置回退本地。本阶段可选客户端禁真出站。
 */
@Component
public class RagDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentParser.class);

    /** PDF 页间分隔符；与 IR 约定一致，便于按页保留页码语义 */
    private static final char PAGE_BREAK = '\f';

    private final RagParseProperties properties;
    private final OptionalParseClients optionalClients;

    /**
     * @param properties      解析配置
     * @param optionalClients MinerU / Vision 可选客户端
     */
    public RagDocumentParser(RagParseProperties properties, OptionalParseClients optionalClients) {
        this.properties = properties;
        this.optionalClients = optionalClients;
    }

    /**
     * 无可选增强的测试工厂：仅本地 PDF/DOCX/TXT/MD。
     *
     * @return 本地解析器
     */
    public static RagDocumentParser forLocalTests() {
        RagParseProperties props = new RagParseProperties();
        props.setEngine("local");
        props.getVision().setEnabled(false);
        props.getMineru().setEnabled(false);
        return new RagDocumentParser(props, new OptionalParseClients(props));
    }

    /**
     * 解析为纯文本。
     *
     * @param fileName 原始文件名，用于判扩展名与引擎分流
     * @param bytes    文件内容
     * @return 正文；可能为空串
     * @throws IllegalArgumentException 格式不支持或解析失败
     */
    public String parse(String fileName, byte[] bytes) {
        if (!RagUploadTypes.allowed(fileName)) {
            throw new IllegalArgumentException("不支持的文件格式，仅支持 " + RagUploadTypes.describe());
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        // 可选 MinerU：就绪才试；失败或空结果按 fallback 回本地
        if (shouldTryMinerU(fileName)) {
            try {
                Optional<String> remote = optionalClients.parseWithMinerU(fileName, bytes);
                if (remote.isPresent() && StringUtils.hasText(remote.get())) {
                    return remote.get();
                }
                if (!properties.getMineru().isFallbackLocal()
                        && "mineru".equalsIgnoreCase(engine())) {
                    throw new IllegalStateException("MinerU 返回空正文且未允许回退本地");
                }
                log.info("[Parse] MinerU 无可用结果，回退本地 file={}", fileName);
            } catch (RuntimeException e) {
                if (properties.getMineru().isFallbackLocal() && !"mineru".equalsIgnoreCase(engine())) {
                    log.warn("[Parse] MinerU 失败，回退本地 file={} reason={}", fileName, e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        return parseLocal(fileName, bytes);
    }

    /**
     * 本地按扩展名分流。
     *
     * @param fileName 文件名
     * @param bytes    内容
     * @return 正文
     */
    String parseLocal(String fileName, byte[] bytes) {
        String ext = RagUploadTypes.extensionOf(fileName);
        return switch (ext) {
            case "txt", "md" -> new String(bytes, StandardCharsets.UTF_8);
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            case "png", "jpg", "jpeg", "gif", "webp" -> parseImage(bytes, RagUploadTypes.mimeOf(ext));
            default -> throw new IllegalArgumentException("不支持的文件格式：" + ext);
        };
    }

    private boolean shouldTryMinerU(String fileName) {
        if ("local".equalsIgnoreCase(engine())) {
            return false;
        }
        return optionalClients.minerUReady(fileName);
    }

    private String engine() {
        String eng = properties.getEngine();
        return eng == null ? "local" : eng.trim();
    }

    /**
     * PDF 逐页抽取文字层；扫描件在 Vision 可用时尝试 OCR，否则明确失败。
     */
    private String parsePdf(byte[] bytes) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) {
                throw new IllegalArgumentException("PDF 没有页面");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                if (page > 1) {
                    sb.append(PAGE_BREAK);
                }
                String text = stripper.getText(document);
                sb.append(text == null ? "" : text);
            }
            String out = sb.toString();
            if (!needsPdfOcr(out, pages)) {
                return out;
            }
            // 扫描件：Vision 未就绪 → 明确失败，禁止静默入库空白
            if (!optionalClients.visionReady()) {
                throw new IllegalArgumentException("PDF 未提取到足够文字（可能是扫描件；请开启 Vision OCR 或提供文字版）");
            }
            Optional<String> ocr = optionalClients.ocrImage(bytes, "application/pdf");
            if (ocr.isPresent() && StringUtils.hasText(ocr.get())) {
                return ocr.get();
            }
            throw new IllegalArgumentException("扫描 PDF OCR 未识别到文字（或本阶段禁出站）");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("PDF 解析失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 平均每页有效字符过低或全文几乎空 → 需要 OCR。
     *
     * @param extracted 文字层
     * @param pageCount 页数
     * @return true 表示应走 OCR
     */
    boolean needsPdfOcr(String extracted, int pageCount) {
        int pages = Math.max(1, pageCount);
        String compact = extracted == null ? "" : extracted.replace("\f", "").replaceAll("\\s+", "");
        if (compact.length() < 20) {
            return true;
        }
        int threshold = properties.getVision().getPdfSparseCharsPerPage();
        return compact.length() / pages < Math.max(10, threshold);
    }

    /**
     * 图片 OCR；Vision 未开/无 Key → 明确失败，不炸成 NPE。
     */
    private String parseImage(byte[] bytes, String mime) {
        if (!optionalClients.visionReady()) {
            throw new IllegalArgumentException("图片入库需要开启 agenthub.rag.parse.vision.enabled 并配置 API Key");
        }
        Optional<String> text = optionalClients.ocrImage(bytes, mime);
        if (text.isEmpty() || !StringUtils.hasText(text.get())) {
            throw new IllegalArgumentException("图片 OCR 未识别到文字（或本阶段禁出站）");
        }
        return text.get();
    }

    /**
     * DOCX：标题样式写成 Markdown ATX，正文段落空行分隔，供后续 HIERARCHICAL。
     */
    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String line = paragraph.getText();
                if (line == null) {
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int heading = headingLevel(paragraph);
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                if (heading > 0) {
                    sb.append("#".repeat(Math.min(heading, 6))).append(' ').append(line);
                } else {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("DOCX 解析失败：" + e.getClass().getSimpleName());
        }
    }

    private static int headingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            style = "";
        }
        String lower = style.toLowerCase(Locale.ROOT);
        if (lower.contains("heading") || style.contains("标题")) {
            for (int i = 6; i >= 1; i--) {
                if (lower.contains("heading" + i) || style.contains("标题 " + i) || style.contains("标题" + i)) {
                    return i;
                }
            }
            return 2;
        }
        return 0;
    }
}
