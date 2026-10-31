package com.agenthub.memory.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个会话的摘要槽加近轮窗口。所属域：memory。
 * 摘要和窗口分 key 存放，这里只是读出来之后的内存形态。
 */
@Data
public class WindowState {

    private String summary = "";
    private List<ContextTurn> turns = new ArrayList<>();
}
