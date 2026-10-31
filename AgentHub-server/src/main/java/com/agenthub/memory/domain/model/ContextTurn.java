package com.agenthub.memory.domain.model;

import lombok.Data;

/**
 * 滑动窗口里的一轮。所属域：memory。只保留角色和正文，不放工具实参。
 */
@Data
public class ContextTurn {

    /** user 或 assistant */
    private String role;
    private String text;

    /**
     * @param role user 或 assistant
     * @param text 正文
     * @return 一轮
     */
    public static ContextTurn of(String role, String text) {
        ContextTurn turn = new ContextTurn();
        turn.setRole(role);
        turn.setText(text == null ? "" : text);
        return turn;
    }
}
