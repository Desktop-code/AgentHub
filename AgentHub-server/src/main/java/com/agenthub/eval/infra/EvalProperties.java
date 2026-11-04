package com.agenthub.eval.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测域配置。所属域：eval 的 infra。
 * <p>只由 {@link EvalInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 不标 {@code @Component}，避免同一配置出现两个 bean。
 * <p>刀4 增补：{@code openjudge.*}（软评分 sidecar）与 {@code report.*}（报告落盘）。
 * <p>刀6 增补：{@code evalscope.*}（可选桥，默认 enabled=false；不进 gate）。
 */
@ConfigurationProperties(prefix = "agenthub.eval")
public class EvalProperties {

    /** 当前租户；必须 &gt; 0（0 保留给平台只读样例）。默认 1 */
    private long currentTenantId = 1L;

    /** 平台样例配置 */
    private Samples samples = new Samples();

    /** 并行度；本刀实现按 1 串行，硬顶可读但勿真并行 */
    private int maxParallel = 1;

    /** 单 case 墙钟超时（毫秒） */
    private long caseTimeoutMs = 180_000L;

    /** case 基础设施 ERROR 后是否继续后续 case；默认 true */
    private boolean continueOnCaseError = true;

    /** 试跑通道标记；写入会话标题前缀语义，现网表无独立 channel 列 */
    private String channel = "EVAL";

    /** OpenJudge sidecar 全局缺省 */
    private OpenJudge openjudge = new OpenJudge();

    /** 报告落盘全局缺省 */
    private Report report = new Report();

    /** EvalScope 可选桥全局缺省（默认关） */
    private EvalScope evalscope = new EvalScope();

    /**
     * @return 当前租户
     */
    public long getCurrentTenantId() {
        return currentTenantId;
    }

    /**
     * @param currentTenantId 当前租户
     */
    public void setCurrentTenantId(long currentTenantId) {
        this.currentTenantId = currentTenantId;
    }

    /**
     * @return 样例配置
     */
    public Samples getSamples() {
        return samples;
    }

    /**
     * @param samples 样例配置
     */
    public void setSamples(Samples samples) {
        this.samples = samples;
    }

    /**
     * @return 并行度
     */
    public int getMaxParallel() {
        return maxParallel;
    }

    /**
     * @param maxParallel 并行度
     */
    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    /**
     * @return 单 case 超时毫秒
     */
    public long getCaseTimeoutMs() {
        return caseTimeoutMs;
    }

    /**
     * @param caseTimeoutMs 单 case 超时毫秒
     */
    public void setCaseTimeoutMs(long caseTimeoutMs) {
        this.caseTimeoutMs = caseTimeoutMs;
    }

    /**
     * @return 出错是否继续
     */
    public boolean isContinueOnCaseError() {
        return continueOnCaseError;
    }

    /**
     * @param continueOnCaseError 出错是否继续
     */
    public void setContinueOnCaseError(boolean continueOnCaseError) {
        this.continueOnCaseError = continueOnCaseError;
    }

    /**
     * @return 通道标记
     */
    public String getChannel() {
        return channel;
    }

    /**
     * @param channel 通道标记
     */
    public void setChannel(String channel) {
        this.channel = channel;
    }

    /**
     * @return OpenJudge 配置
     */
    public OpenJudge getOpenjudge() {
        return openjudge;
    }

    /**
     * @param openjudge OpenJudge 配置
     */
    public void setOpenjudge(OpenJudge openjudge) {
        this.openjudge = openjudge == null ? new OpenJudge() : openjudge;
    }

    /**
     * @return 报告配置
     */
    public Report getReport() {
        return report;
    }

    /**
     * @param report 报告配置
     */
    public void setReport(Report report) {
        this.report = report == null ? new Report() : report;
    }

    /**
     * @return EvalScope 桥配置
     */
    public EvalScope getEvalscope() {
        return evalscope;
    }

    /**
     * @param evalscope EvalScope 桥配置
     */
    public void setEvalscope(EvalScope evalscope) {
        this.evalscope = evalscope == null ? new EvalScope() : evalscope;
    }

    /**
     * 平台样例位置。所属域：eval 的 infra。
     */
    public static class Samples {

        /**
         * 平台样例 classpath 位置（钉死默认；与 {@code EvalSampleCatalog.DEFAULT_LOCATION} 同值）。
         * <p>正文在 JSON，不在 Java；改样例只改资源文件。
         */
        private String location = "classpath:eval/samples/platform-smoke-suite.json";

        /**
         * @return 资源位置
         */
        public String getLocation() {
            return location;
        }

        /**
         * @param location 资源位置
         */
        public void setLocation(String location) {
            this.location = location;
        }
    }

    /**
     * OpenJudge sidecar 缺省。所属域：eval 的 infra。
     * <p>默认 enabled=false（保守）；required=false（不进 gate）。
     */
    public static class OpenJudge {

        /** 全局是否启用；false → 全员 DISABLED，零 HTTP */
        private boolean enabled = false;
        /** 失败是否抬升为质量门禁；本刀仍不改 verdict/gate，只记 status */
        private boolean required = false;
        /** sidecar 基址 */
        private String baseUrl = "http://127.0.0.1:18765";
        /** 单次评分超时毫秒 */
        private long timeoutMs = 60_000L;
        /** 缺省 graders（有 reference / expect_tools 时 Runner 再追加） */
        private List<String> defaultGraders = new ArrayList<>(List.of("RelevanceGrader"));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public List<String> getDefaultGraders() {
            return defaultGraders;
        }

        public void setDefaultGraders(List<String> defaultGraders) {
            this.defaultGraders = defaultGraders == null
                    ? new ArrayList<>(List.of("RelevanceGrader"))
                    : new ArrayList<>(defaultGraders);
        }
    }

    /**
     * 报告落盘缺省。所属域：eval 的 infra。
     */
    public static class Report {

        /** 是否写 workspace 文件 */
        private boolean writeWorkspace = true;
        /** 是否写中文 Markdown 摘要 */
        private boolean markdown = true;
        /** workspace 根目录（相对进程 cwd） */
        private String workspaceDir = "./workspace-test";

        public boolean isWriteWorkspace() {
            return writeWorkspace;
        }

        public void setWriteWorkspace(boolean writeWorkspace) {
            this.writeWorkspace = writeWorkspace;
        }

        public boolean isMarkdown() {
            return markdown;
        }

        public void setMarkdown(boolean markdown) {
            this.markdown = markdown;
        }

        public String getWorkspaceDir() {
            return workspaceDir;
        }

        public void setWorkspaceDir(String workspaceDir) {
            this.workspaceDir = workspaceDir;
        }
    }

    /**
     * EvalScope 可选桥缺省。所属域：eval 的 infra。
     * <p>默认 enabled=false；失败不进 gate；不取代 trial。
     */
    public static class EvalScope {

        /** 全局是否启用；false → 全员 DISABLED，零落盘/零 HTTP */
        private boolean enabled = false;
        /** 外部 sidecar 基址；空则仅允许落盘导出，trigger → SKIPPED */
        private String baseUrl = "";
        /** HTTP 超时毫秒 */
        private long timeoutMs = 60_000L;
        /** 导出根（相对 cwd）；最终落在 {@code {exportDir}/eval-{runId}/evalscope/} */
        private String exportDir = "./workspace-test";
        /** 运维备注（写入清单 meta） */
        private String note = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getExportDir() {
            return exportDir;
        }

        public void setExportDir(String exportDir) {
            this.exportDir = exportDir;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
