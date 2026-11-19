package com.agenthub.runtime.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.common.error.BizException;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PreprocessView;
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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 入口 Router 在意图分流前的附件预处理。所属域：runtime。
 *
 * <p>首页综合对话和 MA 运行入口共用这一份：有图、允许的文档或录音时先识别，
 * 把正文回传到步骤，再交给现有意图分流。没有这些附件时调用方不要进来，避免空步骤。
 * 视觉和语音只用入口方案已绑定的 VISION / ASR，禁止借用业务专家或知识库入库 YAML Key。
 */
@Component
public class EntryPreprocess {

    private static final Logger log = LoggerFactory.getLogger(EntryPreprocess.class);

    /** 过程里回显的识别正文上限，完整版仍进意图上下文 */
    private static final int DISPLAY_LIMIT = 1500;

    private final TrialAttachmentReader documents;
    private final TrialUserFileStorage files;
    private final VisionOcrExecutor vision;
    private final VoiceIoAppService voice;
    private final ModelInstanceRepository instances;
    private final OperatorContext operatorContext;

    /**
     * @param documents       本地抽字 / 扫描页视觉，与管理端对话共用解析器
     * @param files           附件落盘读取
     * @param vision          入口 VISION 出站
     * @param voice           入口 ASR 出站
     * @param instances       校验入口绑定的实例用途与启停
     * @param operatorContext 记账用当前用户
     */
    public EntryPreprocess(TrialAttachmentReader documents, TrialUserFileStorage files, VisionOcrExecutor vision,
                           VoiceIoAppService voice, ModelInstanceRepository instances,
                           OperatorContext operatorContext) {
        this.documents = documents;
        this.files = files;
        this.vision = vision;
        this.voice = voice;
        this.instances = instances;
        this.operatorContext = operatorContext;
    }

    /**
     * 本轮是否需要预处理。纯文字提问必须跳过，行为与现在一致。
     *
     * @param images 已校验图片
     * @param stored 已落盘文件
     * @return true 表示有图、文档或录音
     */
    public static boolean needed(List<String> images, List<UserMessageFiles.Ref> stored) {
        if (images != null && !images.isEmpty()) {
            return true;
        }
        return stored != null && stored.stream().anyMatch(item -> item != null);
    }

    /**
     * 当前方案是不是对话入口。首页和 MA 运行入口都是 {@code ENTRY_ROUTER}。
     *
     * @param spec 本轮方案；可空
     * @return true 表示走入口预处理，而不是管理端已选专家那条链
     */
    public static boolean forEntry(MaRunSpec spec) {
        return spec != null && AgentRuntimeConfig.isEntryRouterTemplate(spec.template());
    }

    /**
     * 先识别再回传。失败抛 {@link TrialFailure}，调用方落失败步骤后停止分流。
     *
     * @param spec     入口方案（读 VISION / ASR 实例）
     * @param session  当前会话
     * @param spoken   用户打的字，可空
     * @param images   已校验图片
     * @param stored   已落盘文件
     * @param progress 进度出口，可空
     * @return 送给意图和专家的正文，以及显式记忆用的用户表述
     * @throws TrialFailure 未绑模型、识别失败或录音不合格
     */
    public Result run(MaRunSpec spec, RunSession session, String spoken, List<String> images,
                      List<UserMessageFiles.Ref> stored, TrialProgress progress) {
        List<String> accepted = images == null ? List.of() : images;
        List<UserMessageFiles.Ref> refs = stored == null ? List.of() : stored;
        List<UserMessageFiles.Ref> audio = refs.stream()
                .filter(item -> item != null && UserMessageFiles.isAudioName(item.name()))
                .toList();
        List<UserMessageFiles.Ref> docs = refs.stream()
                .filter(item -> item != null && !UserMessageFiles.isAudioName(item.name()))
                .toList();
        List<String> tasks = new ArrayList<>();
        if (!accepted.isEmpty()) {
            tasks.add(PreprocessView.TASK_VISION);
        }
        if (!docs.isEmpty()) {
            tasks.add(PreprocessView.TASK_FILE);
        }
        if (!audio.isEmpty()) {
            tasks.add(PreprocessView.TASK_SPEECH);
        }
        Long visionId = optionalBound(spec, ModelCapability.VISION);
        if (!accepted.isEmpty() && visionId == null) {
            throw new TrialFailure("入口未绑定视觉模型，无法识别图片。请到对话路由的「模型配置」绑定视觉模型。");
        }
        Long asrId = requireBound(spec, ModelCapability.ASR, !audio.isEmpty(),
                "入口未绑定语音识别模型，无法转写录音。请到对话路由的「模型配置」绑定 ASR 模型。");
        emit(progress, PreprocessView.running(tasks, runningHint(tasks)));
        String transcript = "";
        if (!audio.isEmpty()) {
            emit(progress, PreprocessView.running(tasks, "正在转写语音"));
            transcript = transcribeAll(asrId, audio, session);
        }
        String spokenUser = AttachmentTexts.spokenWithTranscript(spoken, transcript);
        String fileBlock = "";
        boolean truncated = false;
        if (!docs.isEmpty()) {
            emit(progress, PreprocessView.running(tasks, "正在抽文件"));
            TrialAttachmentReader.Outcome read = documents.apply(visionId, session, "", docs, progress, true);
            fileBlock = read.modelContent() == null ? "" : read.modelContent().trim();
            truncated = read.modelContent() != null && read.modelContent().contains(AttachmentTexts.TRUNCATED);
        }
        String visionNote = "";
        if (!accepted.isEmpty()) {
            emit(progress, PreprocessView.running(tasks, "正在识图"));
            visionNote = recognizeImages(visionId, accepted, session);
        }
        String intent = VisionOcrTexts.composeQuery(visionNote, spokenUser);
        if (fileBlock != null && !fileBlock.isBlank()) {
            intent = intent == null || intent.isBlank() ? fileBlock : intent + "\n\n" + fileBlock;
        }
        if (intent == null || intent.isBlank()) {
            throw new TrialFailure("未能从附件中得到可分流的文本，请补充文字后再发送");
        }
        if (truncated && !intent.contains(AttachmentTexts.TRUNCATED)) {
            intent = intent + "\n" + AttachmentTexts.TRUNCATED;
        }
        // 素材单独留一份：意图用的 intentText 是「素材 + 问句」揉在一起的，判路由可以改写问句，
        // 但子专家必须原样拿到这些事实，不能因为 sub_query 是概述就连票面一起丢。
        String material = materialOf(visionNote, transcript, fileBlock);
        String display = displayOf(spokenUser, visionNote, fileBlock);
        String summary = summaryOf(tasks, display);
        emit(progress, PreprocessView.done(tasks, summary, display));
        log.info("入口预处理完成 sessionId={} tasks={} intentChars={} materialChars={}",
                session == null ? null : session.getId(), tasks, intent.length(), material.length());
        return new Result(intent, material, spokenUser, PreprocessView.done(tasks, summary, display));
    }

    /**
     * 只装「附件派生的事实」：识图正文、语音转写、文件抽字。
     *
     * <p>刻意不含用户手打的字，也不含判路由改写过的问句——素材是事实，问句是意图，
     * 两者在子侧分别落位（问句进【当前用户】，素材跟在它后面）。
     * 段标题沿用既有三类（{@code 【图片内容提取结果】} / {@code 【语音转写】} / {@code 【附件正文】}），
     * 不新造标记，前端与回放的拆分规则因此不用改。
     *
     * @param visionNote 识图汇总文本，可空
     * @param transcript 语音转写文本（不含手打字），可空
     * @param fileBlock  文件抽字段落（已带【附件正文】与文件名），可空
     * @return 拼好的素材段；三类都为空时返回空串
     */
    private static String materialOf(String visionNote, String transcript, String fileBlock) {
        List<String> parts = new ArrayList<>();
        String visionPart = VisionOcrTexts.materialBlock(visionNote);
        if (!visionPart.isBlank()) {
            parts.add(visionPart);
        }
        String speech = transcript == null ? "" : transcript.trim();
        if (!speech.isEmpty()) {
            parts.add(AttachmentTexts.SPEECH_HEADER + "\n" + speech);
        }
        String files = fileBlock == null ? "" : fileBlock.trim();
        if (!files.isEmpty()) {
            parts.add(files);
        }
        return String.join("\n\n", parts);
    }

    /**
     * 推进行中 / 完成 / 失败快照。progress 为空时（同步路径）只跳过直播。
     *
     * @param progress 进度出口
     * @param view     快照
     */
    public void emit(TrialProgress progress, PreprocessView view) {
        if (progress == null || view == null) {
            return;
        }
        String phase = phaseOf(view);
        if (phase != null) {
            progress.phase(phase);
        }
        progress.preprocess(view);
    }

    private String transcribeAll(Long asrId, List<UserMessageFiles.Ref> audio, RunSession session) {
        List<String> parts = new ArrayList<>();
        for (UserMessageFiles.Ref file : audio) {
            byte[] bytes;
            try {
                bytes = files.readBytes(file.url());
            } catch (RuntimeException ex) {
                throw new TrialFailure("无法读取录音「" + file.name() + "」，请重新录制后再发送");
            }
            try {
                String text = voice.transcribeByInstance(asrId, bytes, file.name());
                if (text == null || text.isBlank()) {
                    throw new TrialFailure("没有识别到语音内容，请靠近麦克风再说一次");
                }
                parts.add(text.trim());
            } catch (TrialFailure ex) {
                throw ex;
            } catch (BizException ex) {
                throw new TrialFailure(ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("入口语音转写失败 sessionId={} file={} type={}",
                        session == null ? null : session.getId(), file.name(), ex.getClass().getSimpleName());
                throw new TrialFailure("语音识别失败，请重试或检查入口绑定的 ASR 模型");
            }
        }
        return String.join("\n", parts);
    }

    private String recognizeImages(Long visionId, List<String> images, RunSession session) {
        if (visionId == null) {
            throw new TrialFailure("入口未绑定视觉模型，无法识别图片。请到对话路由的「模型配置」绑定视觉模型。");
        }
        MeterScope scope = MeterScope.console(session.getAgentId(), visionId, session.getId(),
                operatorContext.requireUserId());
        String text = vision.extract(images, visionId, scope);
        if (text == null || text.isBlank() || VisionOcrTexts.FAILED_MARK.equals(text.trim())) {
            throw new TrialFailure("未能从图片中提取到文字，请换一张更清晰的图片重试");
        }
        return text;
    }

    private Long requireBound(MaRunSpec spec, ModelCapability capability, boolean required, String missing) {
        Long id = optionalBound(spec, capability);
        if (!required) {
            return id;
        }
        if (id == null) {
            throw new TrialFailure(missing);
        }
        return id;
    }

    private Long optionalBound(MaRunSpec spec, ModelCapability capability) {
        if (spec == null || spec.runtime() == null || capability == null) {
            return null;
        }
        Long id = capability == ModelCapability.VISION
                ? spec.runtime().getMaVisionModelInstanceId()
                : spec.runtime().getMaAsrModelInstanceId();
        if (id == null) {
            return null;
        }
        ModelInstance instance = instances.findById(id).orElse(null);
        if (instance == null || !RecordStatus.isEnabled(instance.getStatus())) {
            throw new TrialFailure("入口绑定的" + (capability == ModelCapability.VISION ? "视觉" : "语音识别")
                    + "模型实例不可用，请到对话路由的「模型配置」重新选择");
        }
        ModelCapability actual = ModelCapability.tryParse(instance.getCapability()).orElse(null);
        if (actual != capability) {
            throw new TrialFailure("入口绑定的模型用途不是 "
                    + (capability == ModelCapability.VISION ? "视觉" : "语音识别")
                    + "，请到对话路由的「模型配置」改绑");
        }
        return id;
    }

    private static String runningHint(List<String> tasks) {
        if (tasks.contains(PreprocessView.TASK_VISION) && tasks.contains(PreprocessView.TASK_FILE)
                && tasks.contains(PreprocessView.TASK_SPEECH)) {
            return "正在识图 / 抽文件 / 转写语音";
        }
        if (tasks.contains(PreprocessView.TASK_VISION) && tasks.contains(PreprocessView.TASK_FILE)) {
            return "正在识图 / 抽文件";
        }
        if (tasks.contains(PreprocessView.TASK_VISION) && tasks.contains(PreprocessView.TASK_SPEECH)) {
            return "正在识图 / 转写语音";
        }
        if (tasks.contains(PreprocessView.TASK_FILE) && tasks.contains(PreprocessView.TASK_SPEECH)) {
            return "正在抽文件 / 转写语音";
        }
        if (tasks.contains(PreprocessView.TASK_VISION)) {
            return "正在识图";
        }
        if (tasks.contains(PreprocessView.TASK_FILE)) {
            return "正在抽文件";
        }
        return "正在转写语音";
    }

    private static String phaseOf(PreprocessView view) {
        if (view == null) {
            return null;
        }
        if (PreprocessView.RUNNING.equals(view.status())) {
            if (view.hint() != null && view.hint().contains("识图")) {
                return "vision";
            }
            if (view.hint() != null && view.hint().contains("转写")) {
                return "speech";
            }
            return "preprocess";
        }
        return "preprocess";
    }

    private static String displayOf(String spokenUser, String visionNote, String fileBlock) {
        List<String> parts = new ArrayList<>();
        if (spokenUser != null && !spokenUser.isBlank()) {
            parts.add(spokenUser.trim());
        }
        if (visionNote != null && !visionNote.isBlank()) {
            parts.add(visionNote.trim());
        }
        if (fileBlock != null && !fileBlock.isBlank()) {
            parts.add(fileBlock.trim());
        }
        String body = parts.stream().filter(part -> !part.isBlank()).collect(Collectors.joining("\n\n"));
        if (body.length() <= DISPLAY_LIMIT) {
            return body;
        }
        return body.substring(0, DISPLAY_LIMIT) + "\n" + AttachmentTexts.TRUNCATED;
    }

    private static String summaryOf(List<String> tasks, String display) {
        String prefix = tasks.contains(PreprocessView.TASK_SPEECH) ? "已转写并回传"
                : tasks.contains(PreprocessView.TASK_VISION) ? "已识图并回传"
                : "已抽字并回传";
        String body = display == null ? "" : display.replace('\n', ' ').trim();
        if (body.length() > 80) {
            body = body.substring(0, 80) + "…";
        }
        return body.isEmpty() ? prefix : prefix + "：" + body;
    }

    /**
     * @param intentText   送给意图分流的正文（含原话、转写、识图、抽字）；判定模型从这里判意图
     * @param materialText 只含附件派生的事实（识图 / 语音转写 / 文件抽字），交给被分到的专家；
     *                     与 intentText 分开是为了让 {@code sub_query} 改写问句时不连素材一起丢
     * @param memoryText   显式记忆只看用户表述（手打 + 转写），不吃文件全文
     * @param view         完成后的步骤快照，供落库
     */
    public record Result(String intentText, String materialText, String memoryText, PreprocessView view) {
    }
}
