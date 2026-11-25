package com.agenthub.rag.infra.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话附件复用的本地解析：文字层、表格、幻灯片。不走视觉模型。
 */
class RagDocumentParserLocalTest {

    private final RagDocumentParser parser = RagDocumentParser.forLocalTests();

    @Test
    void textAndCsvKeepTheSentence() {
        byte[] txt = "独特句子青柠密语7741".getBytes(StandardCharsets.UTF_8);
        assertTrue(parser.readLocalDocument("note.txt", txt).contains("青柠密语7741"));
        byte[] csv = "项目,金额\n青柠,7741\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(parser.readLocalDocument("金额.csv", csv).contains("7741"));
    }

    @Test
    void pdfPagesSplitCopyableTextFromBlankPage() throws Exception {
        List<String> pages = parser.readPdfPageTexts(samplePdf());
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).contains("Qingning passphrase 7741"));
        assertTrue(pages.get(0).replaceAll("\\s+", "").length() >= 8);
        assertTrue(pages.get(1).replaceAll("\\s+", "").length() < 8);
    }

    @Test
    void blankPdfPageRendersToPngWithoutReadingItAsText() throws Exception {
        byte[] png = parser.renderPdfPagePng(samplePdf(), 2);
        assertTrue(png.length > 8);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
    }

    @Test
    void xlsxAndXlsKeepCellValue() throws Exception {
        assertTrue(parser.readLocalDocument("报价.xlsx", xlsx()).contains("7741"));
        assertTrue(parser.readLocalDocument("报价.xls", xls()).contains("7741"));
    }

    @Test
    void docxAndPptxKeepBody() throws Exception {
        assertTrue(parser.readLocalDocument("说明.docx", docx()).contains("青柠密语7741"));
        assertTrue(parser.readLocalDocument("幻灯.pptx", pptx()).contains("青柠密语7741"));
    }

    @Test
    void legacyDocPptAndZipFailClearly() {
        IllegalArgumentException doc = assertThrows(IllegalArgumentException.class,
                () -> parser.readLocalDocument("旧.doc", new byte[]{1, 2, 3}));
        assertTrue(doc.getMessage().contains("无法读取该文件"));
        assertTrue(doc.getMessage().contains(".doc"));
        IllegalArgumentException ppt = assertThrows(IllegalArgumentException.class,
                () -> parser.readLocalDocument("旧.ppt", new byte[]{1, 2, 3}));
        assertTrue(ppt.getMessage().contains(".ppt"));
        IllegalArgumentException zip = assertThrows(IllegalArgumentException.class,
                () -> parser.readLocalDocument("包.zip", new byte[]{1, 2, 3}));
        assertEquals("目前不能读取压缩包内的文件", zip.getMessage());
    }

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Qingning passphrase 7741");
                stream.endText();
            }
            doc.addPage(new PDPage(PDRectangle.LETTER));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("报价");
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("项目");
            head.createCell(1).setCellValue("金额");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("青柠");
            row.createCell(1).setCellValue(7741);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] xls() throws Exception {
        try (HSSFWorkbook book = new HSSFWorkbook()) {
            var sheet = book.createSheet("报价");
            var row = sheet.createRow(0);
            row.createCell(0).setCellValue("金额");
            row.createCell(1).setCellValue(7741);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("独特句子青柠密语7741");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] pptx() throws Exception {
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlide slide = show.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("独特句子青柠密语7741");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            show.write(out);
            return out.toByteArray();
        }
    }
}
