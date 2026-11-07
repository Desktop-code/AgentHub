package com.agenthub.memory.api.dto;

import lombok.Data;

/**
 * 受控重建记忆向量集合入参。所属域：memory。
 * <p>
 * 两个字段都是「防误删」闸门：
 * <ul>
 *   <li>{@code confirmRebuild}：危险动作的显式确认，缺省即拒绝；前端必须在二次弹窗里明确
 *       「将删除现网记忆向量集合、按新维度重建、有效 LONG 打回 PENDING、需补同步，且不可撤销」后才置 true；</li>
 *   <li>{@code collectionName}：必须与当前配置的记忆集合完全一致，服务端拒绝任何其它名字。</li>
 * </ul>
 */
@Data
public class MemoryVectorRebuildReq {

    /** true 表示操作者已确认删除现网集合并重建 */
    private Boolean confirmRebuild;

    /** 目标集合名；必须等于当前配置的记忆 collection（agenthub_memory_*） */
    private String collectionName;
}
