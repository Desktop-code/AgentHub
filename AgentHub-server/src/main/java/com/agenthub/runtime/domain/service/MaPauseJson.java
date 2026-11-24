package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.AgentChoice;
import com.agenthub.runtime.domain.model.MaPauseMember;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 方案停等状态的 JSON 读写。所属域：runtime。
 *
 * <p>只负责「成员快照 / 已写键 / 问句选项」三块序列化：表里其余标量列由仓储直接映射。
 * 读写失败一律降级为空（读）或空串（写），由调用方决定是「无法续跑」还是「跳过可选块」，
 * 不把 Jackson 异常抛进对话主链路。
 *
 * <p>问句选项复用 {@link WorkbenchChoiceJson}，与直播帧 / 回放事件同一份契约，
 * 避免停等状态里出现第二套选项格式。
 */
@Component
public class MaPauseJson {

    private static final Logger log = LoggerFactory.getLogger(MaPauseJson.class);
    private static final TypeReference<List<MaPauseMember>> MEMBER_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Set<String>> KEY_SET = new TypeReference<>() {
    };

    private final ObjectMapper json;

    /**
     * @param json Spring 统一 ObjectMapper（已带 -parameters 与常用模块）
     */
    public MaPauseJson(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 序列化一位成员的完整快照。
     *
     * @param member 成员；可空
     * @return JSON；空成员返回空串
     */
    public String writeMember(MaPauseMember member) {
        if (member == null) {
            return "";
        }
        try {
            return json.writeValueAsString(member);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等成员序列化失败 type={} cause={}", member.getRouteKey(), ex.getMessage());
            return "";
        }
    }

    /**
     * 读取一位成员。
     *
     * @param raw JSON；可空
     * @return 成员；解析失败返回 null
     */
    public MaPauseMember readMember(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, MaPauseMember.class);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等成员解析失败 cause={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 序列化成员列表（已完成 / 待执行 / 已跳过）。
     *
     * @param members 列表；可空
     * @return JSON 数组；空列表返回空串
     */
    public String writeMembers(List<MaPauseMember> members) {
        if (members == null || members.isEmpty()) {
            return "";
        }
        try {
            return json.writeValueAsString(members);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等成员列表序列化失败 size={} cause={}", members.size(), ex.getMessage());
            return "";
        }
    }

    /**
     * 读取成员列表。
     *
     * @param raw JSON；可空
     * @return 列表；解析失败返回空表
     */
    public List<MaPauseMember> readMembers(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<MaPauseMember> list = json.readValue(raw, MEMBER_LIST);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等成员列表解析失败 cause={}", ex.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 序列化已写路由键集合。
     *
     * @param keys 集合；可空
     * @return JSON 数组；空集合返回空串
     */
    public String writeKeys(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "";
        }
        try {
            return json.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等已写键序列化失败 cause={}", ex.getMessage());
            return "";
        }
    }

    /**
     * 读取已写路由键集合。
     *
     * @param raw JSON；可空
     * @return 集合；解析失败返回空集
     */
    public Set<String> readKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            Set<String> keys = json.readValue(raw, KEY_SET);
            return keys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(keys);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等已写键解析失败 cause={}", ex.getMessage());
            return new LinkedHashSet<>();
        }
    }

    /**
     * 序列化入口参与者身份。
     *
     * @param gateway 入口身份；可空
     * @return JSON；空身份返回空串
     */
    public String writeParticipant(com.agenthub.runtime.domain.model.ParticipantView gateway) {
        if (gateway == null) {
            return "";
        }
        try {
            return json.writeValueAsString(gateway);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等入口身份序列化失败 type={} cause={}", gateway.id(), ex.getMessage());
            return "";
        }
    }

    /**
     * 读取入口参与者身份。
     *
     * @param raw JSON；可空
     * @return 身份；解析失败返回 null（回放少一个入口块，不影响续跑）
     */
    public com.agenthub.runtime.domain.model.ParticipantView readParticipant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, com.agenthub.runtime.domain.model.ParticipantView.class);
        } catch (JsonProcessingException ex) {
            log.warn("方案停等入口身份解析失败 cause={}", ex.getMessage());
            return null;
        }
    }

    /**
     * 写问句 + 选项。停等时选项一定是等待态。
     *
     * @param choice 选项结构；可空
     * @return JSON；没有可点选项时返回空串
     */
    public String writeChoice(AgentChoice choice) {
        return choice == null ? "" : WorkbenchChoiceJson.write(choice.awaiting());
    }

    /**
     * 读问句 + 选项。
     *
     * @param raw JSON；可空
     * @return 等待态选项；解析失败返回 null
     */
    public AgentChoice readChoice(String raw) {
        return WorkbenchChoiceJson.parseJson(raw).map(AgentChoice::awaiting).orElse(null);
    }
}
