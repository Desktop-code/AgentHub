package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.common.error.BizException;
import com.agenthub.model.domain.service.ModelTemperaturePolicy;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.domain.enums.PromptPurpose;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.prompt.domain.model.PromptContext;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.spi.llm.TextCompletionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * 查询改写的运行时实现。所属域：rag 的 application。
 * <p>
 * 能力来源全部是现成的，不新造第二套提示词体系：
 * <ol>
 *   <li>模型：{@link AgentRewriteLookup} 只读 {@code agent_model_bind(purpose=REWRITE)}，
 *       <b>不降级到 CHAT</b>；槽为空即视为不具备改写能力；</li>
 *   <li>提示词：该槽钉死的 REWRITE 版 L2 模板 + 该槽的 L3（覆盖段与追加区）由
 *       {@link PromptAssembler#assemble} 组装，与配置页预览走同一条链路；</li>
 *   <li>出站：{@link TextCompletionClient}，由 runtime 的实现套计量、Trace 与推理兜底。</li>
 * </ol>
 * <p>
 * 失败策略（硬约束）：本类<b>不抛异常</b>。槽空、模板不可用、模型报错、空结果、超长结果
 * 一律返回 {@link Result#failed}/{@link Result#skipped}，由检索层回退原问句继续检索——
 * 改写是增强项，不能因为它把整次检索打失败。
 * <p>
 * 线程：阻塞（最长等模型出站超时），只在工具线程内调用；禁止放进首字路径。
 */
@Service
public class RagQueryRewriteService implements RagQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(RagQueryRewriteService.class);

    /** 改写输出上限：改写句是短句，给足余量即可，避免推理模型把额度全用在思考之外的长篇上 */
    static final int MAX_TOKENS = 512;

    /** 原问上限：更长的文本（整段粘贴）不走改写，直接回退原问，避免无意义出站 */
    static final int MAX_QUERY_CHARS = 2000;

    /** 改写句上限：检索句过长会拖慢嵌入与 BM25，且明显不是「短句」诉求，按失败回退 */
    static final int MAX_REWRITTEN_CHARS = 512;

    /** 提示词语言：与知识库/Agent 默认一致 */
    private static final String DEFAULT_LOCALE = "zh-CN";

    /** 模型习惯性加的包裹符号对（开, 闭）；必须成对出现才剥离，避免误删真正的括号内容 */
    private static final String[][] WRAPPING_PAIRS = {
            {"```", "```"}, {"\"", "\""}, {"'", "'"}, {"“", "”"}, {"‘", "’"},
            {"「", "」"}, {"『", "』"}, {"《", "》"}, {"`", "`"}};

    private final AgentRewriteLookup lookup;
    private final PromptAssembler prompts;
    private final TextCompletionClient completions;

    /**
     * @param lookup      只读 REWRITE 槽（含 L3 四列）
     * @param prompts     L2/L3 统一组装门面
     * @param completions 平台内部文本生成出站（已计量）
     */
    public RagQueryRewriteService(AgentRewriteLookup lookup, PromptAssembler prompts,
                                  TextCompletionClient completions) {
        this.lookup = lookup;
        this.prompts = prompts;
        this.completions = completions;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 门禁顺序：原问合法 → 有 Agent → 槽内有可用 REWRITE 模型 → 提示词可组装 → 出站。
     * 任一门不过都不改问句，并用机器可读原因写清是哪一道门。
     */
    @Override
    public Result rewrite(Caller caller, String query) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) {
            return Result.skipped(original, "empty_query");
        }
        if (caller == null || !caller.present()) {
            return Result.skipped(original, "no_agent");
        }
        if (original.length() > MAX_QUERY_CHARS) {
            return Result.skipped(original, "query_too_long");
        }
        // 第一道门之模型侧：只认 REWRITE 槽，槽空或实例停用都不改写（绝不借 CHAT 模型）
        Optional<AgentModelBind> bind = lookup.enabledRewriteBind(caller.agentId());
        if (bind.isEmpty()) {
            return Result.skipped(original, "rewrite_model_missing");
        }
        Long modelInstanceId = bind.get().getModelInstanceId();
        String system;
        try {
            system = assemblePrompt(bind.get());
        } catch (BizException ex) {
            // 模板未发布 / 缺必选段 / 用途不匹配：属配置问题，回退原问句而不是让检索失败
            log.warn("[RAG] 改写提示词不可用 agentId={} reason={}", caller.agentId(), ex.getMessage());
            return Result.failed(original, "rewrite_prompt_unavailable",
                    ex.getClass().getSimpleName(), modelInstanceId);
        }
        long operatorId = caller.operatorId() == null ? 0L : caller.operatorId();
        String raw;
        try {
            raw = completions.complete(caller.agentId(), caller.sessionId(), operatorId, modelInstanceId,
                    system, null, original,
                    ModelTemperaturePolicy.DEFAULT_DETERMINISTIC, MAX_TOKENS);
        } catch (BizException ex) {
            // 业务失败（Key / 协议未适配 / 模型无正文）：按失败回退，检索仍用原问
            log.warn("[RAG] 改写出站失败 agentId={} modelInstanceId={} type={} msg={}",
                    caller.agentId(), modelInstanceId, ex.getClass().getSimpleName(), ex.getMessage());
            return Result.failed(original, failureReasonOf(ex), ex.getClass().getSimpleName(), modelInstanceId);
        } catch (RuntimeException ex) {
            // 超时 / 中断等未预期异常：同样回退，不让改写把检索带崩
            log.warn("[RAG] 改写异常 agentId={} modelInstanceId={} type={} msg={}",
                    caller.agentId(), modelInstanceId, ex.getClass().getSimpleName(), ex.getMessage());
            return Result.failed(original, failureReasonOf(ex), ex.getClass().getSimpleName(), modelInstanceId);
        }
        String rewritten = normalize(raw);
        if (rewritten.isEmpty()) {
            // 空回很常见（推理模型额度被思考吃满）：明确记空结果，便于与「模型报错」区分
            log.warn("[RAG] 改写返回空结果 agentId={} modelInstanceId={} rawChars={}",
                    caller.agentId(), modelInstanceId, raw == null ? 0 : raw.length());
            return Result.failed(original, "rewrite_empty_result", null, modelInstanceId);
        }
        if (rewritten.length() > MAX_REWRITTEN_CHARS) {
            // 明显不是「检索短句」，宁可用原问，避免把长文塞进嵌入与 BM25
            log.warn("[RAG] 改写结果过长，回退原问 agentId={} chars={}", caller.agentId(), rewritten.length());
            return Result.failed(original, "rewrite_too_long", null, modelInstanceId);
        }
        log.info("[RAG] 查询改写完成 agentId={} modelInstanceId={} originalChars={} rewrittenChars={}",
                caller.agentId(), modelInstanceId, original.length(), rewritten.length());
        return Result.applied(rewritten, modelInstanceId);
    }

    /**
     * 用该槽钉死的 REWRITE 模板 + L3 组装系统段。
     * <p>
     * 走 {@link PromptAssembler#assemble} 而不是只拼锁定段：REWRITE 模板的「改写风格」段
     * 是可覆盖的开放段，正是把口语压成检索短句的那一段，只在锁定段里拼会丢掉它。
     *
     * @param bind REWRITE 绑定行
     * @return 系统段正文
     * @throws BizException 模板未发布、缺必选段、用途不匹配
     */
    private String assemblePrompt(AgentModelBind bind) {
        PromptContext context = PromptContext.builder()
                .purpose(PromptPurpose.REWRITE.name())
                .locale(DEFAULT_LOCALE)
                .templateId(bind.getPromptTemplateId())
                .templateVersion(bind.getTemplateVersion())
                .overrideJson(bind.getPromptOverrideJson())
                .promptExtra(bind.getPromptExtra())
                .build();
        PromptAssemblyResult result = prompts.assemble(context);
        return result.getText();
    }

    /**
     * 把模型输出收成一句检索句。
     * <p>
     * 只做必要的清洗：去 Markdown 代码围栏、去包裹引号、取首个非空行、合并多余空白。
     * 不做语义加工——改写口径属于提示词契约，不在代码里二次改词。
     *
     * @param raw 模型原文，可空
     * @return 规范化后的句子；没有可用正文时为空串
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        // 部分模型会把结果包在 ``` / ```text 里
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            text = firstBreak < 0 ? "" : text.substring(firstBreak + 1);
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
        }
        String picked = "";
        for (String line : text.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.isEmpty()) {
                picked = candidate;
                break;
            }
        }
        picked = stripWrappingQuotes(picked).trim();
        // 折叠行内连续空白：检索句里多空格既无意义也会影响 BM25 分词
        return picked.replaceAll("\\s+", " ");
    }

    /**
     * 去掉模型习惯性加的整句包裹符号（引号 / 书名号 / 反引号），必须成对才算包裹。
     *
     * @param text 单行文本
     * @return 去包裹后的文本
     */
    private static String stripWrappingQuotes(String text) {
        String out = text;
        boolean stripped = true;
        while (stripped && out.length() >= 2) {
            stripped = false;
            for (String[] pair : WRAPPING_PAIRS) {
                if (out.startsWith(pair[0]) && out.endsWith(pair[1]) && out.length() > pair[0].length()) {
                    out = out.substring(pair[0].length(), out.length() - pair[1].length()).trim();
                    stripped = true;
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 从异常里区分「超时」与其它失败，便于 Trace 一眼看出是等太久还是模型报错。
     *
     * @param ex 异常
     * @return 机器可读原因
     */
    static String failureReasonOf(Throwable ex) {
        if (ex == null) {
            return "rewrite_failed";
        }
        String type = ex.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        if (type.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
            return "rewrite_timeout";
        }
        if (ex instanceof InterruptedException || type.contains("interrupt")) {
            return "rewrite_interrupted";
        }
        return "rewrite_model_error";
    }
}
