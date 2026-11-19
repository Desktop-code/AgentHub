package com.agenthub.runtime.infra.persistence;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.service.SessionTurnSource;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.AttachmentTexts;
import com.agenthub.runtime.domain.service.PreprocessJson;
import com.agenthub.runtime.domain.service.UserMessageFiles;
import com.agenthub.runtime.domain.service.UserMessageImages;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用 agent_run_event 回补对话窗口。所属域：runtime。
 * 只取用户和助手轮，工具实参不进窗口。
 *
 * <p>{@link #replay} 只回用户原话，给长期记忆抽取用。
 * {@link #replayForModel} 会在原话后面附上当时写下的附件正文，刷新或续问仍能读到文件；
 * 入口预处理的素材（识图 / 转写 / 抽字）也在这条路径上挂回它所属的那一轮。
 */
@Component
public class RunEventTurnSource implements SessionTurnSource {

    private final RunEventRepository events;
    private final TrialUserFileStorage files;

    /**
     * @param events 运行事件
     * @param files  附件正文旁路；没有旁路文件时模型回放退回原话
     */
    public RunEventTurnSource(RunEventRepository events, TrialUserFileStorage files) {
        this.events = events;
        this.files = files;
    }

    /**
     * {@inheritDoc}
     * 优先正文，没有正文时用短摘要。不附带文件正文。
     */
    @Override
    public List<ContextTurn> replay(long sessionId) {
        return replay(sessionId, false);
    }

    /**
     * {@inheritDoc}
     * 用户轮带上已解析的附件正文。只发文件、当时没留下正文的旧消息仍跳过。
     */
    @Override
    public List<ContextTurn> replayForModel(long sessionId) {
        return replay(sessionId, true);
    }

    private List<ContextTurn> replay(long sessionId, boolean withFiles) {
        List<RunEvent> all = events.listBySessionId(sessionId);
        // 入口素材按「紧跟用户那条 PREPROCESS」归属到该用户轮：入口先落 USER 再落 PREPROCESS
        Map<Integer, String> materialByUserSeq = withFiles ? materialsOf(all) : Map.of();
        List<ContextTurn> turns = new ArrayList<>();
        for (RunEvent event : all) {
            if (event == null || event.getEventType() == null) {
                continue;
            }
            String role;
            if (EventType.USER.code().equals(event.getEventType())) {
                role = "user";
            } else if (EventType.ASSISTANT.code().equals(event.getEventType())) {
                role = "assistant";
            } else {
                continue;
            }
            boolean userTurn = "user".equals(role);
            String material = userTurn ? materialByUserSeq.getOrDefault(event.getSeq(), "") : "";
            // 有图时摘要是 ahimg 清单，不能当成用户原话灌进记忆窗口
            String text = material.isEmpty()
                    ? userText(event, withFiles && userTurn)
                    : withEntryMaterial(event, material);
            if (text.isBlank()) {
                continue;
            }
            turns.add(ContextTurn.of(role, text));
        }
        return turns;
    }

    /**
     * 收集「用户轮 seq → 该轮入口预处理素材」。
     *
     * <p>为什么读落库的 PREPROCESS 正文而不是再认一次图：素材在当次发送时就已经算好并随事件落库，
     * 回放再跑视觉模型既慢又可能得到另一份结果，还会重复计费。
     *
     * @param all 会话全部事件（按 id 升序）
     * @return 用户轮 seq → 素材正文；没有入口预处理的会话为空表
     */
    private static Map<Integer, String> materialsOf(List<RunEvent> all) {
        Map<Integer, String> byUserSeq = new HashMap<>();
        for (RunEvent event : all) {
            if (event == null || !EventType.PREPROCESS.code().equals(event.getEventType())) {
                continue;
            }
            String material = PreprocessJson.materialOf(event.getContentText());
            if (material.isBlank() || event.getSeq() == null) {
                continue;
            }
            // 预处理 seq 紧跟在它所属的用户轮之后（入口先落 USER 再落 PREPROCESS）
            byUserSeq.put(event.getSeq() - 1, material);
        }
        return byUserSeq;
    }

    /**
     * 入口轮的用户侧正文：原话在前、素材在后。
     *
     * <p>刻意不走 {@link AttachmentTexts#compose}：那一步会再包一层 {@code 【附件正文】}，
     * 而入口素材自己已经带着 {@code 【图片内容提取结果】} / {@code 【语音转写】} / {@code 【附件正文】}
     * 三个段标题，再包一层会让同一份票面出现两个父标题。
     *
     * <p>素材已包含文件抽字段落，因此这一支不再走旁路 {@code .extract.txt}，避免同一份正文拼两遍。
     * 只发图 / 只发文件时原话为空，此时整轮只剩素材——这一轮必须留下来，
     * 否则下一轮专家看不到上一轮的票面。
     *
     * @param event    用户事件
     * @param material 该轮的入口素材，非空
     * @return 送进回放窗口的用户轮文本
     */
    private static String withEntryMaterial(RunEvent event, String material) {
        String spoken = UserMessageImages.questionText(event.getContentText(), event.getSummary());
        if (spoken.isBlank()) {
            return material;
        }
        return spoken + "\n\n" + material;
    }

    /**
     * 用户原话。模型回放时，若摘要里有文件且旁路已写下正文，附在原话后面。
     *
     * @param event     事件
     * @param withFiles 是否拼附件正文
     * @return 窗口里的这一轮文本
     */
    private String userText(RunEvent event, boolean withFiles) {
        String spoken = UserMessageImages.questionText(event.getContentText(), event.getSummary());
        if (!withFiles || !UserMessageFiles.looksLikeManifest(event.getSummary())) {
            return spoken;
        }
        List<String> sections = new ArrayList<>();
        for (UserMessageFiles.Item item : UserMessageFiles.itemsOf(event.getSummary())) {
            if (item == null || !"file".equals(item.kind())) {
                continue;
            }
            String excerpt = files.readExcerpt(item.url());
            if (excerpt != null && !excerpt.isBlank()) {
                sections.add(excerpt);
            }
        }
        return AttachmentTexts.compose(spoken, sections).text();
    }
}
