package com.agenthub.agent.domain.service;

import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 平台默认智能助手的锁定口径。所属域：agent。
 *
 * <p>识别方式：只看稳定业务编码 {@link #DEFAULT_ASSISTANT_CODE}（由
 * {@code data-init-workbench-reception.sql} 种子写入 {@code agent_def.code}），
 * 不按名称或主键识别——名称可改、主键各环境不同，只有编码是跨环境稳定的身份。
 *
 * <p>锁三件事：<b>不允许停用、不允许删除、编码不允许改</b>。
 * 名称与简介仍可改（列表与消息流都读库里的同一条定义，不做第二份展示名）；
 * 运行模式、模型等其它字段按原有逻辑处理，本刀不扩。
 *
 * <p>列表与详情隐藏 / 置灰入口只是体验层，真正的兜底在
 * {@link #assertCanChangeStatus} / {@link #assertCanRemove} / {@link #assertCanChangeCode}，
 * 防止绕过页面直调接口。
 */
public final class PlatformDefaultAssistantPolicy {

    /** 平台默认接待 / 智能助手的稳定编码（与种子脚本一致，禁止随手改名） */
    public static final String DEFAULT_ASSISTANT_CODE = "reception_assistant";

    private PlatformDefaultAssistantPolicy() {
    }

    /**
     * 是否平台默认智能助手。
     *
     * @param code Agent 业务编码；可空
     * @return true 表示这条定义属于平台默认兜底接待
     */
    public static boolean isDefaultAssistant(String code) {
        // 编码比较忽略大小写与首尾空白：DBA 手工改过大小写也不该让锁失效
        return code != null && DEFAULT_ASSISTANT_CODE.equalsIgnoreCase(code.trim());
    }

    /**
     * 启停前校验。平台默认助手是兜底接待，停用会让工作台失去默认入口。
     *
     * <p>只拦「改成停用」；允许把误停用的行改回启用，便于自救。
     *
     * @param code   当前定义的编码
     * @param status 目标状态，见 {@link AgentStatus}
     * @throws BizException 目标为停用且编码是平台默认助手时 BAD_REQUEST
     */
    public static void assertCanChangeStatus(String code, int status) {
        if (status != AgentStatus.ENABLED && isDefaultAssistant(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "平台默认智能助手不可禁用");
        }
    }

    /**
     * 删除前校验。平台默认助手不接受逻辑删除，也不接受连带清绑定与路由。
     *
     * @param code 当前定义的编码
     * @throws BizException 编码是平台默认助手时 BAD_REQUEST
     */
    public static void assertCanRemove(String code) {
        if (isDefaultAssistant(code)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "平台默认智能助手不可删除");
        }
    }

    /**
     * 保存前校验编码：平台默认助手的编码只读。
     *
     * <p>为什么必须锁编码而不是只锁页面输入框：整个锁定口径都靠编码识别，
     * 一旦允许把 {@code reception_assistant} 改成别的值，这条定义就脱离了锁定，
     * 之后可以随意停用 / 删除，等于把闸门绕开。
     *
     * <p>只比内容、不比大小写：{@code Reception_Assistant} 这类仅大小写差异的提交
     * 视为未修改，落库仍保留原编码。
     *
     * @param existingCode 库里当前的编码
     * @param targetCode   本次提交的编码（已去首尾空白）
     * @throws BizException 当前是平台默认助手且提交了不同的编码时 BAD_REQUEST
     */
    public static void assertCanChangeCode(String existingCode, String targetCode) {
        if (isDefaultAssistant(existingCode) && !sameCode(existingCode, targetCode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "平台默认智能助手编码不可修改");
        }
    }

    /**
     * 保留编码校验：非默认助手行不得占用平台默认助手的编码。
     *
     * <p>默认助手那一行永远存在（已禁止删除），正常路径下唯一性校验就会拦下重名；
     * 这里补一道语义更明确的拒绝，避免历史上被删过的库里出现第二条同编码定义，
     * 让「哪个才是被锁的兜底」变得含糊。
     *
     * @param targetCode       本次要写入的编码
     * @param defaultRowOwner  true 表示这次写入的就是默认助手那一行本身
     * @throws BizException 想把保留编码写给别的行时 BAD_REQUEST
     */
    public static void assertCodeNotReserved(String targetCode, boolean defaultRowOwner) {
        if (!defaultRowOwner && isDefaultAssistant(targetCode)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该编码为平台默认智能助手保留，请换一个编码");
        }
    }

    /**
     * 编码内容比较：忽略大小写与首尾空白。
     *
     * @param left  一侧编码
     * @param right 另一侧编码
     * @return true 表示视为同一个编码
     */
    private static boolean sameCode(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }
}
