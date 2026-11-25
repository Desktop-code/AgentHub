package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方案停等状态的 JSON 读写。所属域：runtime。
 *
 * <p>重点是「停在哪位、问了什么、哪些已写」这三块在落库 / 读回后不丢：
 * 丢成员身份会让卡片错位，丢 waiting 会让下一句被当成新问题，丢已写键会重复建卡。
 */
class MaPauseJsonTest {

    private final MaPauseJson json = new MaPauseJson(new ObjectMapper());

    @Test
    void memberRoundTripKeepsIdentityAndAnswer() {
        MaPauseMember member = new MaPauseMember();
        member.setRouteKey("agent:designer");
        member.setParticipantId("p-designer");
        member.setParticipantName("界面设计师");
        member.setParticipantKind(ParticipantView.KIND_AGENT);
        member.setParticipantDepth(1);
        member.setSubQuery("先出界面稿");
        member.setAnswer("已经出好三套方案。");
        member.setReason("命中界面设计意图");

        String raw = json.writeMember(member);
        MaPauseMember back = json.readMember(raw);

        assertThat(back).isNotNull();
        assertThat(back.getRouteKey()).isEqualTo("agent:designer");
        assertThat(back.getParticipantId()).isEqualTo("p-designer");
        assertThat(back.getParticipantName()).isEqualTo("界面设计师");
        assertThat(back.getParticipantKind()).isEqualTo(ParticipantView.KIND_AGENT);
        assertThat(back.getParticipantDepth()).isEqualTo(1);
        assertThat(back.getAnswer()).isEqualTo("已经出好三套方案。");
        // 还原后的身份必须还能挂卡（id 为空时回放会退化成无主选项）
        assertThat(back.participant()).isNotNull();
        assertThat(back.participant().name()).isEqualTo("界面设计师");
    }

    @Test
    void memberListRoundTripKeepsOrder() {
        MaPauseMember first = member("agent:a", "专家 A", true);
        MaPauseMember second = member("agent:b", "专家 B", false);

        List<MaPauseMember> back = json.readMembers(json.writeMembers(List.of(first, second)));

        assertThat(back).hasSize(2);
        assertThat(back.get(0).getRouteKey()).isEqualTo("agent:a");
        assertThat(back.get(1).getRouteKey()).isEqualTo("agent:b");
        // 失败标记是布尔 isXxx 语义，读回不能变成默认 false
        assertThat(back.get(0).isFailed()).isTrue();
    }

    @Test
    void choiceRoundTripKeepsWaitingAndOptions() {
        AgentChoice waiting = AgentChoice.of("用哪个技术栈？", List.of(
                new AgentChoice.Option("vue3", "Vue3 + Vite + TS"),
                new AgentChoice.Option("java", "Java 单体"))).awaiting();
        assertThat(waiting).isNotNull();

        AgentChoice back = json.readChoice(json.writeChoice(waiting));

        assertThat(back).isNotNull();
        assertThat(back.question()).isEqualTo("用哪个技术栈？");
        assertThat(back.options()).hasSize(2);
        assertThat(back.options().get(1).id()).isEqualTo("java");
        // waiting 必须活过落库：否则刷新后下一句会走入口判路由而不是续跑
        assertThat(back.waiting()).isTrue();
    }

    @Test
    void repliedChoiceRoundTripKeepsReplyAndDropsOptions() {
        AgentChoice replied = new AgentChoice("用哪个技术栈？", List.of(), "Java 单体", false);
        assertThat(replied.hasReply()).isTrue();

        AgentChoice back = json.readChoice(json.writeChoice(replied));

        assertThat(back).isNotNull();
        assertThat(back.options()).isEmpty();
        assertThat(back.reply()).isEqualTo("Java 单体");
        /*
         * 停等表的 options_json 恒按「等待态」写读：它描述的是「这次停在哪句询问」，
         * 不是时间线上那条已答复事件。答复态由 MaReplyResumer 直接写事件，不经过本类。
         */
        assertThat(back.waiting()).isTrue();
    }

    @Test
    void keysRoundTripKeepsWrittenOrder() {
        Set<String> keys = new LinkedHashSet<>(List.of("gateway", "agent:a", "agent:b"));

        assertThat(json.readKeys(json.writeKeys(keys))).containsExactly("gateway", "agent:a", "agent:b");
    }

    @Test
    void gatewayIdentityRoundTrip() {
        ParticipantView gateway = ParticipantView.gateway("p-entry", "智能网关");

        ParticipantView back = json.readParticipant(json.writeParticipant(gateway));

        assertThat(back).isNotNull();
        assertThat(back.id()).isEqualTo("p-entry");
        assertThat(back.kind()).isEqualTo(ParticipantView.KIND_GATEWAY);
    }

    @Test
    void brokenJsonDegradesInsteadOfThrowing() {
        // 坏 JSON 不能让停等读取把会话主链路打挂：降级为空 / 空串即可
        assertThat(json.readMember("{not json")).isNull();
        assertThat(json.readMembers("{not json")).isEmpty();
        assertThat(json.readKeys("{not json")).isEmpty();
        assertThat(json.readChoice("{not json")).isNull();
        assertThat(json.readParticipant("{not json")).isNull();
        assertThat(json.writeMember(null)).isEmpty();
        assertThat(json.writeChoice(null)).isEmpty();
    }

    /**
     * 造一位最小成员。
     *
     * @param routeKey 路由键
     * @param name     展示名
     * @param failed   是否失败
     * @return 成员
     */
    private static MaPauseMember member(String routeKey, String name, boolean failed) {
        MaPauseMember member = new MaPauseMember();
        member.setRouteKey(routeKey);
        member.setParticipantId("p-" + name);
        member.setParticipantName(name);
        member.setParticipantKind(ParticipantView.KIND_AGENT);
        member.setFailed(failed);
        return member;
    }
}
