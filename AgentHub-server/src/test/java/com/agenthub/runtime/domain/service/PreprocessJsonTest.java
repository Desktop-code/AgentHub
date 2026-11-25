package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.PreprocessView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入口预处理 JSON 契约。所属域：runtime。
 *
 * <p>SSE、内存快照和落库回放共用同一份字段，解析失败必须放弃而不是拖垮整轮。
 */
class PreprocessJsonTest {

    @Test
    void frameKeepsStatusTasksAndText() {
        PreprocessView view = PreprocessView.done(List.of(PreprocessView.TASK_FILE),
                "已抽字并回传：你好", "你好");
        Map<String, Object> frame = PreprocessJson.frame(view);
        assertEquals("done", frame.get("status"));
        assertEquals(List.of("file"), frame.get("tasks"));
        assertEquals("已抽字并回传：你好", frame.get("summary"));
        assertEquals("你好", frame.get("text"));
    }

    @Test
    void parseRoundTrip() {
        PreprocessView view = PreprocessView.running(List.of(PreprocessView.TASK_VISION), "正在识图");
        PreprocessView parsed = PreprocessJson.parse(PreprocessJson.payload(view));
        assertNotNull(parsed);
        assertEquals(PreprocessView.RUNNING, parsed.status());
        assertEquals(List.of(PreprocessView.TASK_VISION), parsed.tasks());
        assertEquals("正在识图", parsed.hint());
    }

    @Test
    void parseRejectsBrokenJson() {
        assertNull(PreprocessJson.parse("not-json"));
        assertNull(PreprocessJson.parse("{"));
        assertNull(PreprocessJson.parse(""));
    }

    @Test
    void summaryPrefersHintWhileRunning() {
        PreprocessView running = PreprocessView.running(List.of(PreprocessView.TASK_SPEECH), "正在转写语音");
        assertTrue(PreprocessJson.summaryOf(running).contains("转写"));
        PreprocessView failed = PreprocessView.failed(List.of(), "入口未绑定视觉模型");
        assertTrue(PreprocessJson.summaryOf(failed).contains("未绑定"));
    }

    /**
     * 落库正文多带一份素材，SSE 帧不跟着变大：素材只在回放时读。
     */
    @Test
    void payloadCarriesMaterialButFrameDoesNot() {
        PreprocessView view = PreprocessView.done(List.of(PreprocessView.TASK_VISION),
                "已识图并回传：发票", "[图1内容]: 旅客运输服务");
        String material = VisionOcrTexts.materialBlock("[图1内容]: 旅客运输服务");

        String payload = PreprocessJson.payload(view, material);
        assertEquals(material, PreprocessJson.materialOf(payload));
        // 展示用的正文与字段一个不少
        PreprocessView parsed = PreprocessJson.parse(payload);
        assertNotNull(parsed);
        assertEquals("[图1内容]: 旅客运输服务", parsed.text());
        // SSE 帧不含素材键
        assertFalse(PreprocessJson.frame(view).containsKey("material"));
    }

    /** 旧事件没有 material 键：回放按「没有素材」处理，不抛错。 */
    @Test
    void materialOfToleratesLegacyPayload() {
        PreprocessView view = PreprocessView.done(List.of(PreprocessView.TASK_FILE), "已抽字并回传", "正文");
        assertEquals("", PreprocessJson.materialOf(PreprocessJson.payload(view)));
        assertEquals("", PreprocessJson.materialOf(null));
        assertEquals("", PreprocessJson.materialOf("not-json"));
    }

    /** 素材为空时不写这个键，避免每条纯文字轮都多一个空字段。 */
    @Test
    void payloadSkipsBlankMaterial() {
        PreprocessView view = PreprocessView.done(List.of(), "已识别", "正文");
        String payload = PreprocessJson.payload(view, "   ");
        assertFalse(payload.contains("material"));
        assertEquals("", PreprocessJson.materialOf(payload));
    }
}
