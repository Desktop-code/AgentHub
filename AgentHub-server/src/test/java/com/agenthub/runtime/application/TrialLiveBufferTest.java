package com.agenthub.runtime.application;

import com.agenthub.runtime.api.dto.EventResp;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.service.ParticipantJson;
import com.agenthub.runtime.domain.service.TrialProgress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进行中一轮的内存快照。所属域：runtime。
 * 关注三件事：增量是否合并成一条、seq 是否连续、结束后是否立刻清空。
 */
class TrialLiveBufferTest {

    @Test
    void deltasMergeIntoOneEvent() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(7L, 1, "今天天气");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(7L);
        recorder.assistant("今天");
        recorder.assistant("多云");
        recorder.assistant("。");

        List<EventResp> snapshot = buffer.snapshot(7L);
        // 用户 1 条 + 合并后的助手 1 条，而不是每个 token 一条
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.get(0).getEventType()).isEqualTo("user");
        assertThat(snapshot.get(0).getSeq()).isEqualTo(1);
        assertThat(snapshot.get(1).getEventType()).isEqualTo("assistant");
        assertThat(snapshot.get(1).getContentText()).isEqualTo("今天多云。");
        // 续写不推新序号
        assertThat(snapshot.get(1).getSeq()).isEqualTo(2);
    }

    @Test
    void reasoningAndAssistantStayApart() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(8L, 1, "问");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(8L);
        recorder.reasoning("想");
        recorder.reasoning("一下");
        recorder.assistant("答");
        recorder.reasoning("再想");
        recorder.assistant("再答");

        List<EventResp> snapshot = buffer.snapshot(8L);
        assertThat(snapshot).extracting(EventResp::getEventType)
                .containsExactly("user", "reasoning", "assistant", "reasoning", "assistant");
        assertThat(snapshot).extracting(EventResp::getSeq).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void toolEventKeepsCallLineAndResult() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(9L, 1, "问");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(9L);
        recorder.tool("weather", "{\"city\":\"北京\"}", "晴 25 度", true);
        recorder.tool("weather", "{}", "超时", false);

        List<EventResp> snapshot = buffer.snapshot(9L);
        assertThat(snapshot).hasSize(3);
        EventResp ok = snapshot.get(1);
        assertThat(ok.getEventType()).isEqualTo("tool");
        // 首行调用行，空行后是结果；前端按「\n\n」拆分
        assertThat(ok.getContentText()).contains("调用工具 weather").contains("\n\n").contains("晴 25 度");
        // 失败要在调用行上标出来，与落库口径一致
        assertThat(snapshot.get(2).getContentText()).contains("· 失败");
    }

    @Test
    void blankTextIsSkipped() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(10L, 1, "问");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(10L);
        recorder.phase("planning");
        recorder.reasoning("");
        recorder.route("   ");

        // phase 不进时间线；空白增量与空白路由都不产生记录
        assertThat(buffer.snapshot(10L)).hasSize(1);
    }

    @Test
    void clearRemovesSnapshot() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(11L, 1, "问");
        assertThat(buffer.running(11L)).isTrue();
        assertThat(buffer.snapshot(11L)).hasSize(1);

        buffer.clear(11L);

        // 整轮结束后接口返回空，前端据此回退到落库事件
        assertThat(buffer.running(11L)).isFalse();
        assertThat(buffer.snapshot(11L)).isEmpty();
        assertThat(buffer.recorder(11L)).isNull();
    }

    @Test
    void snapshotIsACopy() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(12L, 1, "问");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(12L);
        recorder.assistant("第一段");

        List<EventResp> first = buffer.snapshot(12L);
        recorder.assistant("第二段");

        // 已取走的副本不受后续写入影响，序列化时不会与写入线程抢同一批对象
        assertThat(first.get(1).getContentText()).isEqualTo("第一段");
        assertThat(buffer.snapshot(12L).get(1).getContentText()).isEqualTo("第一段第二段");
    }

    @Test
    void beginAtSkippedUserEventForApprove() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        // 批准不是新的用户输入：从该轮下一条序号开始镜像，不重复写 user 事件
        buffer.beginAt(13L, 5);
        TrialLiveBuffer.Recorder recorder = buffer.recorder(13L);
        recorder.plan(new com.agenthub.runtime.domain.model.PlanView("p1",
                com.agenthub.runtime.domain.model.PlanState.EXECUTING, List.of()));
        recorder.step(new com.agenthub.runtime.domain.model.StepView(1, "一步",
                com.agenthub.runtime.domain.model.StepState.RUNNING, ""));

        List<EventResp> snapshot = buffer.snapshot(13L);
        assertThat(snapshot).extracting(EventResp::getEventType).containsExactly("plan", "step");
        // 序号必须接在已有落库事件之后，前端按 seq 合并才不会错位
        assertThat(snapshot).extracting(EventResp::getSeq).containsExactly(5, 6);
    }

    @Test
    void unknownSessionHasNoSnapshot() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        assertThat(buffer.running(404L)).isFalse();
        assertThat(buffer.snapshot(404L)).isEmpty();
        assertThat(buffer.recorder(404L)).isNull();
    }

    @Test
    void participantEndIsRecorded() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.begin(14L, 1, "搞个2048小游戏玩玩");
        TrialLiveBuffer.Recorder recorder = buffer.recorder(14L);
        ParticipantView scheme = ParticipantView.scheme("p-0-root-game_team", "游戏研发团队", "game_team", 0);
        ParticipantView design = ParticipantView.agent(
                "p-1-game_team-game_play_design", "游戏玩法设计", "game_play_design", 1);
        ParticipantView implement = ParticipantView.agent(
                "p-1-game_team-game_implement", "游戏实现", "game_implement", 1);
        recorder.beginParticipant(scheme);
        recorder.beginParticipant(design);
        recorder.assistant("规则稿如下");
        recorder.endParticipant(design, TrialProgress.PARTICIPANT_DONE, "");
        recorder.beginParticipant(implement);

        List<EventResp> snapshot = buffer.snapshot(14L);
        /*
         * 刷新页读的就是这份快照：只有 begin 时，早已跑完的成员会和正在跑的那位一起转圈。
         * 终态必须紧跟在该成员之后进快照，形状与出站帧一致（begin=false + state）。
         */
        assertThat(snapshot).extracting(EventResp::getEventType)
                .containsExactly("user", "participant", "participant", "assistant", "participant", "participant");
        String end = snapshot.get(4).getContentText();
        assertThat(ParticipantJson.isBegin(end)).isFalse();
        assertThat(end).contains("\"id\":\"p-1-game_team-game_play_design\"")
                .contains("\"begin\":false")
                .contains("\"state\":\"done\"");
        // 只收口真正结束的那一位：方案根与正在跑的成员仍是 begin，不能被顺手关掉
        assertThat(ParticipantJson.isBegin(snapshot.get(1).getContentText())).isTrue();
        assertThat(ParticipantJson.isBegin(snapshot.get(5).getContentText())).isTrue();
    }

    @Test
    void participantEndKeepsFailureNote() {
        TrialLiveBuffer buffer = new TrialLiveBuffer();
        buffer.beginAt(15L, 5);
        TrialLiveBuffer.Recorder recorder = buffer.recorder(15L);
        ParticipantView review = ParticipantView.agent(
                "p-1-game_team-game_review", "游戏点评", "game_review", 1);
        recorder.beginParticipant(review);
        recorder.endParticipant(review, TrialProgress.PARTICIPANT_FAILED, "撞限：max_rounds（超过 8 轮）");

        String end = buffer.snapshot(15L).stream()
                .filter(item -> "participant".equals(item.getEventType()))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getContentText();
        // 失败说明随快照一起刷新可见：刷新后不应只看到一个还在转的空壳
        assertThat(end).contains("\"state\":\"failed\"").contains("撞限");
    }
}
