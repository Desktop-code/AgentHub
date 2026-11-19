package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.rag.domain.service.RagUploadTypes;
import com.agenthub.rag.infra.parse.RagDocumentParser;
import com.agenthub.rag.infra.parse.RagLocalTexts;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.service.AttachmentTexts;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import com.agenthub.runtime.domain.service.VisionOcrTexts;
import com.agenthub.runtime.infra.agentscope.VisionOcrExecutor;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 把已落盘的对话附件读成模型上下文。所属域：runtime。
 *
 * <p>只服务管理端 Agent 对话。调用方不要把它用在工作台。
 * 文字、表格、演示文稿和 PDF 文字层复用知识库的本地解析；
 * 普通图片不进这里。PDF 里已经能抽出文字的页不再渲染，
 * 只有几乎没字的页才交给该 Agent 已绑定的视觉模型。
 */
@Component
public class TrialAttachmentReader {

    private static final Logger log = LoggerFactory.getLogger(TrialAttachmentReader.class);

    /** 单页 PNG 超过这个体积就不再送视觉模型，避免把一轮卡死在超大图上 */
    private static final int MAX_PAGE_PNG = 6 * 1024 * 1024;

    private final RagDocumentParser parser;
    private final TrialUserFileStorage files;
    private final VisionOcrExecutor vision;
    private final TrialBindingLoader loader;
    private final OperatorContext operatorContext;

    /**
     * @param parser          知识库本地解析（PDF 文字层 / DOCX，以及同包的表格和幻灯片）
     * @param files           附件落盘与正文旁路
     * @param vision          已有的视觉调用。只用于抽不出字的 PDF 页
     * @param loader          解析该 Agent 是否绑定了视觉模型
     * @param operatorContext 视觉调用记账用的当前用户
     */
    public TrialAttachmentReader(RagDocumentParser parser, TrialUserFileStorage files, VisionOcrExecutor vision,
                                 TrialBindingLoader loader, OperatorContext operatorContext) {
        this.parser = parser;
        this.files = files;
        this.vision = vision;
        this.loader = loader;
        this.operatorContext = operatorContext;
    }

    /**
     * 读本轮文件，拼进用户原话。单个文件失败不影响其它文件，也不中断这一轮。
     *
     * @param agent    当前 Agent，可空。没有视觉槽时图片页只说明无法识别
     * @param session  当前会话
     * @param spoken   用户打的字，可空
     * @param stored   已经落盘的文件，顺序与发送一致
     * @param progress 进度出口，可空。第一次需要认图片页时切到 vision 阶段
     * @return 送给模型的用户话，以及需要展示给用户的短说明
     */
    public Outcome apply(AgentDef agent, RunSession session, String spoken,
                         List<UserMessageFiles.Ref> stored, TrialProgress progress) {
        return apply(visionId(agent), session, spoken, stored, progress, false);
    }

    /**
     * 按指定视觉实例抽文档。入口预处理走这一条：扫描页缺视觉或识别失败直接中断，不猜专家。
     *
     * @param visionId               入口或 Agent 已绑定的 VISION 实例；未绑为 null
     * @param session                当前会话
     * @param spoken                 用户打的字，可空
     * @param stored                 已经落盘的文件
     * @param progress               进度出口，可空
     * @param failWhenVisionMissing  true 时扫描页必须能认，否则抛 {@link TrialFailure}
     * @return 送给模型的用户话，以及需要展示给用户的短说明
     * @throws TrialFailure 入口要求视觉却没绑或识别失败
     */
    public Outcome apply(Long visionId, RunSession session, String spoken,
                         List<UserMessageFiles.Ref> stored, TrialProgress progress,
                         boolean failWhenVisionMissing) {
        List<UserMessageFiles.Ref> refs = stored == null ? List.of() : stored;
        int[] visionUsed = new int[1];
        boolean[] announced = new boolean[1];
        List<String> sections = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        for (UserMessageFiles.Ref file : refs) {
            if (file == null) {
                continue;
            }
            if (UserMessageFiles.isAudioName(file.name())) {
                // 录音不走本地抽字，入口预处理另做转写；管理端已选专家对话只存不读
                continue;
            }
            String section = one(file, session, visionId, visionUsed, announced, progress, notices,
                    failWhenVisionMissing);
            if (section != null && !section.isBlank()) {
                sections.add(section);
            }
            // 旁路记下这一段：刷新或续问走回放时不再重新认图，口径与当次发送一致
            try {
                files.writeExcerpt(file.url(), section);
            } catch (RuntimeException ex) {
                log.warn("附件正文未写入旁路 sessionId={} file={} type={}",
                        session == null ? null : session.getId(), file.name(), ex.getClass().getSimpleName());
            }
        }
        AttachmentTexts.Composed composed = AttachmentTexts.compose(spoken, sections);
        if (composed.truncated()) {
            notices.add("附件正文过长，已截断，以上为前段");
        }
        return new Outcome(composed.text(), List.copyOf(notices));
    }

    private String one(UserMessageFiles.Ref file, RunSession session, Long visionId, int[] visionUsed,
                       boolean[] announced, TrialProgress progress, List<String> notices,
                       boolean failWhenVisionMissing) {
        String ext = RagUploadTypes.extensionOf(file.name());
        try {
            if ("pdf".equals(ext)) {
                byte[] bytes = files.readBytes(file.url());
                List<String> pages = parser.readPdfPageTexts(bytes);
                if (failWhenVisionMissing && needsVision(pages) && visionId == null) {
                    throw new TrialFailure("入口未绑定视觉模型，无法识别扫描件。请到对话路由的「模型配置」绑定视觉模型。");
                }
                AttachmentTexts.PdfRead read = AttachmentTexts.readPdf(file.name(), pages, new AttachmentTexts.PageVision() {
                    @Override
                    public boolean available() {
                        return visionId != null;
                    }

                    @Override
                    public String recognize(int pageNumber) {
                        String text = recognizePage(bytes, pageNumber, visionId, session, progress, announced);
                        if (failWhenVisionMissing && (text == null || text.isBlank())) {
                            throw new TrialFailure("入口视觉模型未能识别「" + file.name() + "」第 " + pageNumber
                                    + " 页，已停止分流。请换一份更清晰的扫描件或检查视觉模型。");
                        }
                        return text;
                    }
                }, visionUsed);
                if (failWhenVisionMissing) {
                    for (String notice : read.notices()) {
                        if (notice != null && notice.contains("未绑定视觉模型")) {
                            throw new TrialFailure("入口未绑定视觉模型，无法识别扫描件。请到对话路由的「模型配置」绑定视觉模型。");
                        }
                        if (notice != null && notice.contains("图片识别失败")) {
                            throw new TrialFailure(notice + "。已停止分流。");
                        }
                    }
                }
                notices.addAll(read.notices());
                log.info("附件正文 sessionId={} file={} ext=pdf pages={}",
                        session == null ? null : session.getId(), file.name(), pages.size());
                return read.section();
            }
            if ("zip".equals(ext) || "doc".equals(ext) || "ppt".equals(ext)) {
                // 这些格式本地解析器本来就不解开 / 不支持，直接说明，不去试视觉模型
                String message = unsupported(ext);
                notices.add(file.name() + "：" + message);
                return AttachmentTexts.section(file.name(), message);
            }
            byte[] bytes = files.readBytes(file.url());
            String text = parser.readLocalDocument(file.name(), bytes);
            if (text == null || text.isBlank()) {
                notices.add(file.name() + "：" + AttachmentTexts.UNREADABLE);
                return AttachmentTexts.section(file.name(), AttachmentTexts.UNREADABLE);
            }
            log.info("附件正文 sessionId={} file={} ext={} chars={}",
                    session == null ? null : session.getId(), file.name(), ext, text.length());
            return AttachmentTexts.section(file.name(), text);
        } catch (TrialFailure ex) {
            // 入口扫描件缺视觉或识别失败：必须中断分流，不能收成「无法读取」后继续猜专家
            throw ex;
        } catch (RuntimeException ex) {
            String message = userMessage(ex);
            log.warn("附件正文读取失败 sessionId={} file={} ext={} type={}",
                    session == null ? null : session.getId(), file.name(), ext, ex.getClass().getSimpleName());
            notices.add(file.name() + "：" + message);
            return AttachmentTexts.section(file.name(), message);
        }
    }

    private String recognizePage(byte[] pdf, int pageNumber, Long visionId, RunSession session,
                                 TrialProgress progress, boolean[] announced) {
        if (session == null || session.getId() == null || visionId == null) {
            return "";
        }
        byte[] png;
        try {
            png = parser.renderPdfPagePng(pdf, pageNumber);
        } catch (RuntimeException ex) {
            log.warn("PDF 页渲染失败 sessionId={} page={} type={}",
                    session == null ? null : session.getId(), pageNumber, ex.getClass().getSimpleName());
            return "";
        }
        if (png == null || png.length == 0 || png.length > MAX_PAGE_PNG) {
            return "";
        }
        if (progress != null && !announced[0]) {
            progress.phase("vision");
            announced[0] = true;
        }
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        try {
            MeterScope scope = MeterScope.console(session.getAgentId(), visionId, session.getId(),
                    operatorContext.requireUserId());
            String aggregated = vision.extract(List.of(dataUrl), visionId, scope);
            return stripVision(aggregated);
        } catch (RuntimeException ex) {
            // 单页失败只留下说明，不让这一页拖垮同条消息里已经抽出的文字
            log.warn("PDF 页识别失败 sessionId={} page={} type={}",
                    session.getId(), pageNumber, ex.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 这份 PDF 是否有几乎没字、必须走视觉的页。
     *
     * @param pages 按页文字层
     * @return true 表示至少一页需要识图
     */
    private static boolean needsVision(List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return false;
        }
        for (String page : pages) {
            if (!AttachmentTexts.pageHasText(page)) {
                return true;
            }
        }
        return false;
    }

    private Long visionId(AgentDef agent) {
        if (agent == null || agent.getId() == null) {
            return null;
        }
        Optional<Long> bound = loader.findVisionModel(agent.getId());
        return bound.orElse(null);
    }

    /**
     * 旧格式和压缩包走本地解析器自己的失败文案，避免请求里再拼一套说法。
     *
     * @param ext 小写扩展名
     * @return 给用户和模型看的短说明
     */
    private static String unsupported(String ext) {
        try {
            return RagLocalTexts.document("a." + ext, new byte[]{1});
        } catch (IllegalArgumentException ex) {
            return userMessage(ex);
        }
    }

    private static String userMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message != null && (message.startsWith("无法读取") || message.startsWith("目前不能读取"))) {
            return message;
        }
        return AttachmentTexts.UNREADABLE;
    }

    private static String stripVision(String aggregated) {
        if (aggregated == null || aggregated.isBlank()) {
            return "";
        }
        String prefix = "[图1内容]: ";
        String text = aggregated.trim();
        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length()).trim();
        }
        if (text.isBlank() || VisionOcrTexts.FAILED_MARK.equals(text)) {
            return "";
        }
        return text;
    }

    /**
     * @param modelContent 送给对话模型的用户话。气泡不使用这段全文
     * @param notices      失败、压缩包、图片页无法识别等短说明
     */
    public record Outcome(String modelContent, List<String> notices) {

        public Outcome {
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }
}
