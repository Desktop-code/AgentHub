package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落库骨架必须保留节点文件行的用例。所属域：runtime 的 domain。
 *
 * <p>钉住本刀的前提：「哪个节点写出了哪个文件」只存在落库骨架里
 * （{@code workflow_node_run} 没有这份字段，文件本身在 run 的工作区目录）。
 * 压缩骨架会剥节点产出、压正文、必要时丢过程步，但文件行必须留下，
 * 否则刷新回放无从恢复（{@link com.agenthub.runtime.application.WorkflowCardRebuilder}
 * 正是从这份骨架把文件搬回重建后的卡）。
 *
 * <p>边界：纯序列化，不连库、不写事件。
 */
class WorkflowRunJsonFilesTest {

    /**
     * 常规骨架：文件行原样保留，且只写文件名 / 体积。
     */
    @Test
    @DisplayName("落库骨架保留子图内部节点的文件行")
    void compactKeepsNodeFiles() {
        String stored = WorkflowRunJson.compact(view("终答", 0));

        assertTrue(stored.contains("\"files\""), "骨架要留文件行：" + stored);
        assertTrue(stored.contains("guess-number.html"));
        assertTrue(stored.contains("11586"));
        // 节点正文不进骨架（真源在库），文件行不能跟着被剥掉
        assertFalse(stored.contains("\"output\""));
    }

    /**
     * 正文超长时逐档压缩：过程步先丢、正文再压，文件行仍在（节点表没有被整块放弃）。
     */
    @Test
    @DisplayName("正文超长压缩后文件行仍在")
    void oversizeCompactionKeepsNodeFiles() {
        String stored = WorkflowRunJson.compact(view("很长的正文".repeat(2000), 8));

        assertTrue(stored.length() <= WorkflowRunJson.STORAGE_LIMIT,
                "骨架不能超过落库上限：" + stored.length());
        assertTrue(stored.contains("guess-number.html"), "压缩不能把文件行丢掉：" + stored);
    }

    /**
     * 把直播帧再收一次（停止补落库走这条）：文件行保留，节点产出照旧剥掉。
     */
    @Test
    @DisplayName("直播帧重新收成骨架时保留文件行")
    void forStorageKeepsNodeFiles() {
        String live = WorkflowRunJson.write(view("终答", 2));
        assertTrue(live.contains("\"output\""), "前提：直播帧带节点产出");

        String stored = WorkflowRunJson.forStorage(live);

        assertTrue(stored.contains("guess-number.html"));
        assertFalse(stored.contains("\"output\""));
    }

    /**
     * 造一张带文件行的卡。
     *
     * @param answer 正文
     * @param steps  每个节点的过程步数（用于造超限骨架）
     * @return 视图
     */
    private static WorkflowRunView view(String answer, int steps) {
        List<WorkflowRunView.Step> process = new java.util.ArrayList<>();
        for (int index = 0; index < steps; index += 1) {
            process.add(new WorkflowRunView.Step("REASONING", "", "思考过程 " + index, ""));
        }
        WorkflowRunView.Node inner = new WorkflowRunView.Node(
                "x8f9629e7_impl_agent", "游戏实现", "AGENT", "SUCCESS", "已完成", "写好了页面",
                process, List.of(new WorkflowRunView.NodeFile("guess-number.html", 11586)),
                "", 1, "impl_check", 0, 0, "", "");
        return new WorkflowRunView("小游戏研发", "mini_game_dev", "SUCCESS", answer,
                List.of(inner), null, null, 139L);
    }
}
