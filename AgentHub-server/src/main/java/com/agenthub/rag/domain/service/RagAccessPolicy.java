package com.agenthub.rag.domain.service;

import com.agenthub.rag.domain.model.RagIsolationMode;
import com.agenthub.rag.domain.model.RagKbStatus;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.domain.model.RagVisibility;

/**
 * 知识库可访问性判定。所属域：rag。
 * <p>
 * **唯一判据**：列表过滤、绑定校验、运行时检索三处必须都调这里，
 * 禁止各自写一套「能不能用」的判断——否则很容易出现「列表里看不到但运行时能查到」这种泄漏。
 * <p>
 * 一期规则：
 * <ul>
 *   <li>状态必须是 READY：INIT_FAILED 没有集合，DISABLED 已停用；</li>
 *   <li>可见性 PUBLIC 对所有平台用户可见；PRIVATE 仅创建人与持有管理权限的角色可见
 *       （调用方传入是否管理员）；</li>
 *   <li>RESTRICTED 一期不落地，一律拒绝，避免「看着私密其实全开」；</li>
 *   <li>LOGICAL 一期不落地，同样拒绝。</li>
 * </ul>
 * 二期接入 rag_kb_grant 后，只需扩展本类，不必改调用方。
 */
public final class RagAccessPolicy {

    private RagAccessPolicy() {
    }

    /**
     * 该知识库此刻能否被检索/绑定（与具体用户无关的硬条件）。
     *
     * @param kb 知识库
     * @return true 表示状态与隔离模式都可用
     */
    public static boolean usable(RagKnowledgeBase kb) {
        if (kb == null) {
            return false;
        }
        if (RagKbStatus.parse(kb.getStatus()) != RagKbStatus.READY) {
            return false;
        }
        // RESTRICTED 未接授权表；LOGICAL 未实现共集合过滤
        if (RagVisibility.parse(kb.getVisibility()) == RagVisibility.RESTRICTED) {
            return false;
        }
        return RagIsolationMode.parse(kb.getIsolationMode()) != RagIsolationMode.LOGICAL;
    }

    /**
     * 某个平台用户能否看到并使用该知识库。
     *
     * @param kb        知识库
     * @param userId    当前平台用户，可空
     * @param privileged 是否具备全量可见（如平台管理员）
     * @return true 表示可见可用
     */
    public static boolean visibleTo(RagKnowledgeBase kb, Long userId, boolean privileged) {
        if (!usable(kb)) {
            return false;
        }
        if (privileged) {
            return true;
        }
        RagVisibility visibility = RagVisibility.parse(kb.getVisibility());
        if (visibility == RagVisibility.PUBLIC) {
            return true;
        }
        // PRIVATE：仅创建人。RESTRICTED 已在 usable() 里拒绝
        return userId != null && kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId);
    }

    /**
     * 拒绝原因，供接口与 Tool 返回可读文案。可访问时返回 null。
     *
     * @param kb 知识库
     * @return 不可用原因；可用时为 null
     */
    public static String rejectReason(RagKnowledgeBase kb) {
        if (kb == null) {
            return "知识库不存在";
        }
        RagKbStatus status = RagKbStatus.parse(kb.getStatus());
        if (status == RagKbStatus.INIT_FAILED) {
            return "知识库「" + kb.getName() + "」初始化失败，请先在知识库页重建集合";
        }
        if (status == RagKbStatus.DISABLED) {
            return "知识库「" + kb.getName() + "」已停用";
        }
        if (RagVisibility.parse(kb.getVisibility()) == RagVisibility.RESTRICTED) {
            return "知识库「" + kb.getName() + "」为指定授权库，二期末开放";
        }
        if (RagIsolationMode.parse(kb.getIsolationMode()) == RagIsolationMode.LOGICAL) {
            return "知识库「" + kb.getName() + "」为逻辑隔离库，二期末开放";
        }
        return null;
    }
}
