/**
 * Workflow 域包。所属域：workflow。
 * <p>职责：可视化 DAG 的定义、草稿、发布快照与图校验。
 * <p>本刀边界：只做「能建图 / 能存草稿 / 能校验 / 能发布」，不实现 DAG 调度、
 * 试跑、SSE workflow_*、HUMAN 待办与任何模型/工具出站。
 */
package com.agenthub.workflow;
