package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 计划执行模式的提示词组装。所属域：runtime。
 *
 * <p>约束：平台前缀必须仍在最前——规划与执行都直接复用装配好的系统提示
 * （前缀 + 业务段 + Skill + 工具目录），只在末尾追加「本阶段要求」。
 * 因此不需要另建一份带前缀的模板，也不会绕过 {@link com.agenthub.runtime.infra.agentscope.AssembledPromptGuard}。
 */
@Component
public class PlanPromptFactory {

    /**
     * 规划阶段：要求输出有序 JSON 步骤，且不超过 maxSteps。
     *
     * @param systemPrompt 已装配的系统提示（含平台前缀、业务段、Skill、工具目录），非空
     * @param maxSteps     步数上限，来自 Agent 运行配置
     * @return 交给规划模型的系统提示
     */
    public String planningPrompt(String systemPrompt, int maxSteps) {
        StringBuilder prompt = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        prompt.append("\n\n【本阶段任务：制定计划】\n");
        prompt.append("你是任务规划器。先把用户请求拆成有序、可独立执行的步骤，再交给执行器逐步完成。\n");
        prompt.append("- 只输出 JSON 数组，不要解释、不要 Markdown 代码块以外的任何文字。\n");
        prompt.append("- 每项形如 {\"seq\":1,\"title\":\"一句话标题\",\"detail\":\"该步要做什么\"}。\n");
        prompt.append("- seq 从 1 连续递增；步骤顺序即执行顺序，后一步可以使用前面步骤的结果。\n");
        prompt.append("- 最多 ").append(maxSteps).append(" 步；能一步完成就不要拆多步。\n");
        prompt.append("- 步骤只能使用平台已绑定并启用的工具，不要规划未提供的工具或外部接口。\n");
        prompt.append("- 不得在计划中编造事实或数据；需要数据时把「查询什么」写进步骤，由执行阶段取真实结果。\n");
        prompt.append("- 用户请求本身只需一步即可完成时，数组长度为 1。\n");
        return prompt.toString();
    }

    /**
     * 执行阶段：把整份计划、本步位置与用户原始请求告诉模型，限定它只做这一步。
     *
     * @param systemPrompt  已装配的系统提示，非空
     * @param steps         整份计划（按 seq 升序）
     * @param current       本步
     * @param userText      用户原始请求。必须带上：步骤标题常是「查询天气」这种省略主语/对象的写法，
     *                      缺了原问就无法确定查谁、查哪
     * @param doneSummaries 已完成步骤的结果摘要，按执行顺序；用于让本步接上前文
     * @return 交给执行模型的系统提示
     */
    public String stepPrompt(String systemPrompt, List<PlanStep> steps, PlanStep current, String userText,
                             List<String> doneSummaries) {
        StringBuilder prompt = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        prompt.append("\n\n【本阶段任务：执行计划中的第 ").append(current.seq()).append(" 步】\n");
        prompt.append("用户原始请求：").append(userText == null ? "" : userText.trim()).append('\n');
        prompt.append("整体计划：\n");
        for (PlanStep step : steps) {
            prompt.append(step.seq() == current.seq() ? "→ " : "  ")
                    .append(step.seq()).append(". ").append(step.title());
            if (!step.detail().isEmpty() && step.seq() == current.seq()) {
                prompt.append("（").append(step.detail()).append("）");
            }
            prompt.append('\n');
        }
        if (doneSummaries != null && !doneSummaries.isEmpty()) {
            prompt.append("\n前面步骤的结果：\n");
            int index = 1;
            for (String summary : doneSummaries) {
                prompt.append(index++).append(". ").append(summary).append('\n');
            }
        }
        prompt.append("\n要求：\n");
        prompt.append("- 只完成第 ").append(current.seq()).append(" 步，不要提前做后续步骤，也不要重复已完成步骤。\n");
        prompt.append("- 需要数据时调用已绑定工具取真实结果；工具没有返回的内容不得编造。\n");
        prompt.append("- 直接给出本步的结果，简洁中文，不要输出「第 N 步」「下一步」这类过程旁白。\n");
        return prompt.toString();
    }

    /**
     * 汇总阶段：把所有步骤结果合成一篇终答。
     *
     * @param systemPrompt 已装配的系统提示，非空
     * @param userText     用户原始请求
     * @param stepResults  各步结果，按执行顺序
     * @return 交给汇总模型的系统提示
     */
    public String summaryPrompt(String systemPrompt, String userText, List<String> stepResults) {
        StringBuilder prompt = new StringBuilder(systemPrompt == null ? "" : systemPrompt);
        prompt.append("\n\n【本阶段任务：汇总答复】\n");
        prompt.append("下面是一次任务执行的过程与各步结果。请综合成一篇连贯、完整的中文答复。\n");
        prompt.append("- 不要罗列步骤编号，也不要提「计划」「第几步」这类内部过程。\n");
        prompt.append("- 不要编造各步结果里没有的事实；某步失败就说明该部分未能完成。\n");
        prompt.append("- 用户原问：").append(userText == null ? "" : userText.trim()).append('\n');
        prompt.append("各步结果：\n");
        int index = 1;
        for (String result : stepResults) {
            prompt.append(index++).append(". ").append(result).append('\n');
        }
        return prompt.toString();
    }

    /**
     * 汇总阶段的用户侧输入。用规划时那份已注入记忆的文本，再接各步结果，
     * 这样「那明天呢」这类追问在终答时仍有上文。
     *
     * @param modelUser   规划时已注入摘要/近轮/短期记忆的用户侧文本
     * @param stepResults 各步结果
     * @return 用户消息正文
     */
    public String summaryUser(String modelUser, List<String> stepResults) {
        StringBuilder text = new StringBuilder();
        text.append(modelUser == null ? "" : modelUser.trim()).append("\n\n");
        text.append("各步结果：\n");
        int index = 1;
        for (String result : stepResults) {
            text.append(index++).append(". ").append(result).append('\n');
        }
        return text.toString();
    }
}
