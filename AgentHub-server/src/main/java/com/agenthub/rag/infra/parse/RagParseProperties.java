package com.agenthub.rag.infra.parse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文档解析配置。所属域：rag 的 infra。
 * <p>
 * 默认本地引擎；MinerU / Vision 可选。无 Key 时跳过可选引擎，不抛配置爆炸。
 */
@ConfigurationProperties(prefix = "agenthub.rag.parse")
public class RagParseProperties {

    /**
     * 解析引擎：{@code auto}（有 Key 试 MinerU）/ {@code local} / {@code mineru}（强制，失败才抛）。
     */
    private String engine = "local";

    private final Mineru mineru = new Mineru();
    private final Vision vision = new Vision();

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public Mineru getMineru() {
        return mineru;
    }

    public Vision getVision() {
        return vision;
    }

    /**
     * MinerU 云端解析。
     */
    public static class Mineru {
        /** 总开关；还需配置 api-key 才真正出站 */
        private boolean enabled = false;
        /** 失败是否回退本地；强制 engine=mineru 时为 false 才向上抛 */
        private boolean fallbackLocal = true;
        /** API Key；优先环境变量 AGENTHUB_MINERU_API_KEY */
        private String apiKey = "";
        /** 白名单扩展名（不含 md/txt） */
        private String types = "pdf,docx,png,jpg,jpeg,gif,webp";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFallbackLocal() {
            return fallbackLocal;
        }

        public void setFallbackLocal(boolean fallbackLocal) {
            this.fallbackLocal = fallbackLocal;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getTypes() {
            return types;
        }

        public void setTypes(String types) {
            this.types = types;
        }
    }

    /**
     * 视觉 OCR（扫描 PDF / 图片）。
     */
    public static class Vision {
        /** 总开关；还需配置 api-key */
        private boolean enabled = false;
        /** API Key；优先环境变量 AGENTHUB_VISION_API_KEY */
        private String apiKey = "";
        /** 平均每页有效字符低于此值视为扫描件 */
        private int pdfSparseCharsPerPage = 40;
        /** OCR 最多页数 */
        private int pdfMaxOcrPages = 15;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getPdfSparseCharsPerPage() {
            return pdfSparseCharsPerPage;
        }

        public void setPdfSparseCharsPerPage(int pdfSparseCharsPerPage) {
            this.pdfSparseCharsPerPage = pdfSparseCharsPerPage;
        }

        public int getPdfMaxOcrPages() {
            return pdfMaxOcrPages;
        }

        public void setPdfMaxOcrPages(int pdfMaxOcrPages) {
            this.pdfMaxOcrPages = pdfMaxOcrPages;
        }
    }
}
