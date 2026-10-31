/**
 * 提示词模板库。所属域：prompt。
 * <p>
 * 分层：{@code api} → {@code application} → {@code domain} → {@code infra}。
 * <p>
 * 已交付能力：
 * <ol>
 *   <li>L1 平台约束与 L2 用途模板的管理端（草稿 / 版本 / 发布 / is_active）；</li>
 *   <li>{@code PromptAssembler}：L1 → L2（core 锁定 / soft 可覆盖）→ L3（覆盖 / 追加 / 历史业务段）
 *       → 一次性非递归变量替换 → 预算裁剪，CHAT 与非 CHAT 共用一条链路；</li>
 *   <li>缓存 Caffeine → Redis → DB，发布后显式失效，未命中走单飞；</li>
 *   <li>无生效 L1 / 用途未配置模板 / 缺必选段一律阻断出站，禁止拼半截 Prompt。</li>
 * </ol>
 * <p>
 * 边界：Runtime 注入（tools / skills / RAG / memory）不在本域，仍由 runtime 的
 * {@code PromptInjectionComposer} 在装配之后追加；本域不得反向依赖 runtime。
 * L3 的表单 UI 与 Agent 侧保存校验属后续交付。
 */
package com.agenthub.prompt;
