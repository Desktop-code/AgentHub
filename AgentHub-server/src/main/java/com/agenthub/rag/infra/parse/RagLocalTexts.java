package com.agenthub.rag.infra.parse;

import com.agenthub.rag.domain.service.RagUploadTypes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地文档抽文字。所属域：rag 的 infra。
 *
 * <p>对话附件与知识库入库共用这一套：PDFBox 按页抽文字层、POI 读 DOCX 以外的表格和演示文稿、
 * UTF-8 读纯文本。这里不做 MinerU，也不把整份文件送去视觉 OCR。
 * 旧版 {@code .doc} / {@code .ppt} 不在现有依赖里（没有 poi-scratchpad），调用方要给出明确失败。
 */
public final class RagLocalTexts {

    /** 单个工作表最多读这么多行。超出保留前段并注明，避免一张超大表把对话上下文撑满 */
    private static final int MAX_ROWS = 5_000;

    /** 一行最多读这么多列，与表格工具的列硬顶对齐 */
    private static final int MAX_COLS = 64;

    /** 渲染扫描页用的分辨率。够认字，又把单页 PNG 压在视觉模型能接受的体积里 */
    private static final float PDF_DPI = 120f;

    private RagLocalTexts() {
    }

    /**
     * 按 UTF-8 读纯文本，并去掉开头的 BOM。csv 与 txt、md 同一条路径。
     *
     * @param bytes 文件字节，可空
     * @return 正文；空输入返回空串
     */
    public static String utf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /**
     * PDF 逐页抽可复制文字，不渲染、不 OCR。
     *
     * <p>页序与文件一致，空白页也占一个位置，这样「第几页没字」不会把后面的页号挤乱。
     *
     * @param bytes PDF 字节
     * @return 每页文字；没有页面时抛出
     * @throws IllegalArgumentException 文件损坏或没有页面
     */
    public static List<String> pdfPages(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("无法读取该文件");
        }
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) {
                throw new IllegalArgumentException("无法读取该文件");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            List<String> texts = new ArrayList<>(pages);
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                texts.add(text == null ? "" : text);
            }
            return List.copyOf(texts);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法读取该文件");
        }
    }

    /**
     * 把 PDF 的一页渲染成 PNG，供已经抽不出字的页走视觉模型。
     *
     * @param bytes      PDF 字节
     * @param pageNumber 页码，从 1 开始
     * @return PNG 字节
     * @throws IllegalArgumentException 页码越界或渲染失败
     */
    public static byte[] renderPdfPage(byte[] bytes, int pageNumber) {
        if (bytes == null || bytes.length == 0 || pageNumber < 1) {
            throw new IllegalArgumentException("无法读取该文件");
        }
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes))) {
            if (pageNumber > document.getNumberOfPages()) {
                throw new IllegalArgumentException("无法读取该文件");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, PDF_DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalArgumentException("无法读取该文件");
            }
            return out.toByteArray();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法读取该文件");
        }
    }

    /**
     * 读 xlsx / xls。全部工作表按行展开，公式取计算后的显示值。
     *
     * @param bytes 表格字节
     * @return 带工作表名的文本；空表返回空串
     * @throws IllegalArgumentException 文件损坏
     */
    public static String workbook(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("无法读取该文件");
        }
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = new ByteArrayInputStream(bytes);
             Workbook workbook = WorkbookFactory.create(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            StringBuilder text = new StringBuilder();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append("工作表「").append(oneLine(sheet.getSheetName())).append("」\n");
                int written = appendSheet(text, sheet, formatter, evaluator);
                if (written >= MAX_ROWS) {
                    text.append("（表格过长，已截断，以上为前段）\n");
                }
            }
            return text.toString().trim();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法读取该文件");
        }
    }

    /**
     * 读 pptx 的文本框和表格。旧版 ppt 不在这里处理。
     *
     * @param bytes 演示文稿字节
     * @return 按幻灯片序号拼出的文字
     * @throws IllegalArgumentException 文件损坏
     */
    public static String slides(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("无法读取该文件");
        }
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            int index = 1;
            for (XSLFSlide slide : show.getSlides()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append("第").append(index).append("页：\n");
                if (slide != null) {
                    for (XSLFShape shape : slide.getShapes()) {
                        appendShape(text, shape);
                    }
                }
                index++;
            }
            return text.toString().trim();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法读取该文件");
        }
    }

    /**
     * 对话侧按扩展名抽文字。pdf 不走这里，避免和按页决策搅在一起。
     *
     * @param fileName 原始文件名
     * @param bytes    文件字节
     * @return 正文
     * @throws IllegalArgumentException 格式不能读或文件损坏。文案可直接给用户
     */
    public static String document(String fileName, byte[] bytes) {
        String ext = RagUploadTypes.extensionOf(fileName);
        return switch (ext) {
            case "txt", "md", "csv" -> utf8(bytes);
            case "xlsx", "xls" -> workbook(bytes);
            case "pptx" -> slides(bytes);
            case "doc" -> throw new IllegalArgumentException(
                    "无法读取该文件：当前不能解析旧版 Word（.doc），请另存为 .docx 后再发送");
            case "ppt" -> throw new IllegalArgumentException(
                    "无法读取该文件：当前不能解析旧版 PowerPoint（.ppt），请另存为 .pptx 后再发送");
            case "zip" -> throw new IllegalArgumentException("目前不能读取压缩包内的文件");
            default -> throw new IllegalArgumentException("无法读取该文件");
        };
    }

    private static int appendSheet(StringBuilder text, Sheet sheet, DataFormatter formatter,
                                   FormulaEvaluator evaluator) {
        int written = 0;
        boolean wide = false;
        int lastRow = sheet.getLastRowNum();
        for (int i = 0; i <= lastRow && written < MAX_ROWS; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            int lastCell = row.getLastCellNum();
            if (lastCell < 0) {
                continue;
            }
            if (lastCell > MAX_COLS) {
                wide = true;
            }
            int cols = Math.min(lastCell, MAX_COLS);
            StringBuilder line = new StringBuilder();
            boolean any = false;
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                String value = "";
                if (cell != null) {
                    try {
                        value = formatter.formatCellValue(cell, evaluator);
                    } catch (RuntimeException ex) {
                        // 个别公式算不出来时保留空单元格，不让整张表失败
                        value = formatter.formatCellValue(cell);
                    }
                }
                if (c > 0) {
                    line.append('\t');
                }
                String cellText = value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').trim();
                if (!cellText.isEmpty()) {
                    any = true;
                }
                line.append(cellText);
            }
            if (!any) {
                continue;
            }
            text.append(line).append('\n');
            written++;
        }
        if (wide) {
            text.append("（列数过多，已截断）\n");
        }
        return written;
    }

    private static void appendShape(StringBuilder text, XSLFShape shape) {
        if (shape instanceof XSLFTable table) {
            for (XSLFTableRow row : table.getRows()) {
                StringBuilder line = new StringBuilder();
                int col = 0;
                for (XSLFTableCell cell : row.getCells()) {
                    if (col > 0) {
                        line.append('\t');
                    }
                    String value = cell == null ? "" : cell.getText();
                    line.append(value == null ? "" : value.replace('\n', ' ').trim());
                    col++;
                }
                if (!line.toString().isBlank()) {
                    text.append(line).append('\n');
                }
            }
            return;
        }
        if (shape instanceof XSLFGroupShape group) {
            for (XSLFShape child : group.getShapes()) {
                appendShape(text, child);
            }
            return;
        }
        if (shape instanceof XSLFTextShape textShape) {
            String value = textShape.getText();
            if (value != null && !value.isBlank()) {
                text.append(value.trim()).append('\n');
            }
        }
    }

    private static String oneLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未命名";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
