package com.agenthub.runtime.infra.persistence;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.runtime.domain.model.EventType;
import com.agenthub.runtime.domain.model.PreprocessView;
import com.agenthub.runtime.domain.model.RunEvent;
import com.agenthub.runtime.domain.repository.RunEventRepository;
import com.agenthub.runtime.domain.service.PreprocessJson;
import com.agenthub.runtime.domain.service.VisionOcrTexts;
import com.agenthub.runtime.infra.storage.TrialUserFileStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 入口轮的素材要能跨轮回放。所属域：runtime（回放侧）。
 *
 * <p>只发图那一轮的用户事件正文是空的，修前会被整轮跳过，于是下一轮专家
 * 在【最近对话】里看不到票面、只能再问一遍用户。这里的用例钉住：
 * 素材从落库的 PREPROCESS 正文里取回，并挂到它所属的那条用户轮上。
 */
class RunEventTurnSourceEntryMaterialTest {

    /** 票面素材形状与 {@link VisionOcrTexts#materialBlock} 一致 */
    private static final String MATERIAL = VisionOcrTexts.materialBlock(
            "[图1内容]: 旅客运输服务 电子发票（普通发票）\n发票号码：26447000002316009224");

    @Test
    void imageOnlyTurnSurvivesReplayWithMaterial() {
        RunEventRepository events = mock(RunEventRepository.class);
        // 只发图：用户事件正文为空，摘要只有 ahimg 清单
        when(events.listBySessionId(1L)).thenReturn(List.of(
                user(1, 1, "", "ahimg:1:1:aaa.png"),
                preprocess(2, 2, MATERIAL),
                assistant(3, 3, "我目前只能拿到这张图片…"),
                user(4, 10, "帮我走报销流程", null)));

        RunEventTurnSource source = new RunEventTurnSource(events, mock(TrialUserFileStorage.class));
        List<ContextTurn> turns = source.replayForModel(1L);

        assertEquals(3, turns.size(), "只发图那一轮不能被跳过");
        String first = turns.get(0).getText();
        assertTrue(first.contains("26447000002316009224"), first);
        assertFalse(first.contains("ahimg:"), "清单不是用户原话，不能进窗口");
        assertEquals("帮我走报销流程", turns.get(2).getText());
    }

    @Test
    void materialIsAttachedToItsOwnTurnOnly() {
        RunEventRepository events = mock(RunEventRepository.class);
        String later = VisionOcrTexts.materialBlock("[图1内容]: 第二张票\n发票号码：999");
        when(events.listBySessionId(1L)).thenReturn(List.of(
                user(1, 1, "第一问", null),
                preprocess(2, 2, MATERIAL),
                user(3, 9, "第二问", null),
                preprocess(4, 10, later)));

        RunEventTurnSource source = new RunEventTurnSource(events, mock(TrialUserFileStorage.class));
        List<ContextTurn> turns = source.replayForModel(1L);

        assertEquals(2, turns.size());
        assertTrue(turns.get(0).getText().startsWith("第一问"), turns.get(0).getText());
        assertTrue(turns.get(0).getText().contains("26447000002316009224"), turns.get(0).getText());
        assertFalse(turns.get(0).getText().contains("999"), "别的轮的素材不能串进来");
        assertTrue(turns.get(1).getText().startsWith("第二问"), turns.get(1).getText());
        assertTrue(turns.get(1).getText().contains("999"), turns.get(1).getText());
    }

    /** 长期记忆抽取的回放（{@code replay}）不吃素材，与「抽取只看用户表述」的既有约定一致。 */
    @Test
    void plainReplayDoesNotIncludeMaterial() {
        RunEventRepository events = mock(RunEventRepository.class);
        when(events.listBySessionId(1L)).thenReturn(List.of(
                user(1, 1, "", "ahimg:1:1:aaa.png"),
                preprocess(2, 2, MATERIAL)));

        RunEventTurnSource source = new RunEventTurnSource(events, mock(TrialUserFileStorage.class));
        List<ContextTurn> turns = source.replay(1L);

        assertTrue(turns.isEmpty(), "纯图轮在抽取口径里没有用户表述，仍跳过");
    }

    private static RunEvent user(int id, int seq, String content, String summary) {
        return event(id, seq, EventType.USER, summary, content);
    }

    private static RunEvent assistant(int id, int seq, String content) {
        return event(id, seq, EventType.ASSISTANT, content, content);
    }

    private static RunEvent preprocess(int id, int seq, String material) {
        PreprocessView view = PreprocessView.done(List.of(PreprocessView.TASK_VISION), "已识图并回传", material);
        return event(id, seq, EventType.PREPROCESS, PreprocessJson.summaryOf(view),
                PreprocessJson.payload(view, material));
    }

    private static RunEvent event(int id, int seq, EventType type, String summary, String content) {
        RunEvent event = new RunEvent();
        event.setId((long) id);
        event.setSeq(seq);
        event.setEventType(type.code());
        event.setSummary(summary);
        event.setContentText(content == null || content.isEmpty() ? null : content);
        return event;
    }
}
