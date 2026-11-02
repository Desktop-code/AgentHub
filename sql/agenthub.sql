/*
 Navicat Premium Data Transfer

 Source Server         : localhost_3306
 Source Server Type    : MySQL
 Source Server Version : 50733
 Source Host           : localhost:3306
 Source Schema         : agenthub

 Target Server Type    : MySQL
 Target Server Version : 50733
 File Encoding         : 65001

 Date: 01/11/2026 01:30:53
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for agent_def
-- ----------------------------
DROP TABLE IF EXISTS `agent_def`;
CREATE TABLE `agent_def`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名',
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务编码（未删范围内唯一，应用层校验）',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '简介',
  `run_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'REACT / ROUTER',
  `system_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '系统提示词；ROUTER 父节点也可写路由说明',
  `prompt_template_id` bigint(20) NULL DEFAULT NULL COMMENT 'CHAT 引用 L2 模板 id',
  `template_version` int(11) NULL DEFAULT NULL COMMENT '钉死的 L2 版本号',
  `prompt_override_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'L3 soft 覆盖 JSON：seg_key->文案',
  `prompt_extra` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'L3 追加区（append-only，不可覆盖锁定段）',
  `timeout_ms` bigint(20) NULL DEFAULT NULL COMMENT '整次墙钟上限ms，空=跟YAML',
  `max_rounds` int(11) NULL DEFAULT NULL COMMENT 'ReAct最大轮数，空=跟YAML',
  `tool_timeout_ms` bigint(20) NULL DEFAULT NULL COMMENT '单tool超时ms，空=跟YAML',
  `tool_max_retries` int(11) NULL DEFAULT NULL COMMENT '单tool最大重试，空=跟YAML',
  `max_tool_calls` int(11) NULL DEFAULT NULL COMMENT '单次运行最大tool次数，空=跟YAML',
  `llm_timeout_ms` bigint(20) NULL DEFAULT NULL COMMENT '单次LLM读超时ms，空=跟YAML',
  `max_llm_calls` int(11) NULL DEFAULT NULL COMMENT '单次运行最大LLM次数，空=跟YAML',
  `consecutive_fail_abort` int(11) NULL DEFAULT NULL COMMENT '连续失败N次熔断，空=跟YAML',
  `tool_result_max_chars` int(11) NULL DEFAULT NULL COMMENT '工具结果回灌上限字符，空=跟YAML',
  `runtime_config_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '运行配置JSON：multiIntentEnabled/answerMode/maxRouteChildren等',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_code`(`code`) USING BTREE,
  INDEX `idx_run_mode`(`run_mode`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 定义' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_kb_bind
-- ----------------------------
DROP TABLE IF EXISTS `agent_kb_bind`;
CREATE TABLE `agent_kb_bind`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户',
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id',
  `kb_id` bigint(20) NOT NULL COMMENT 'rag_knowledge_base.id',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1启用 0停用；停用后不进运行时检索',
  `overrides_json` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '检索覆盖项 JSON，可空',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent`(`agent_id`) USING BTREE,
  INDEX `idx_kb`(`kb_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 知识库绑定' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_model_bind
-- ----------------------------
DROP TABLE IF EXISTS `agent_model_bind`;
CREATE TABLE `agent_model_bind`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id',
  `model_instance_id` bigint(20) NOT NULL COMMENT 'model_instance.id',
  `purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用途槽；CHAT/ROUTER/REWRITE/SUMMARY/MEMORY/…；空则跟实例；每槽≤3；priority 越小越先',
  `prompt_template_id` bigint(20) NULL DEFAULT NULL COMMENT '非CHAT 用途引用 L2 模板 id',
  `template_version` int(11) NULL DEFAULT NULL COMMENT '钉死的 L2 版本号',
  `prompt_override_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'L3 soft 覆盖 JSON',
  `prompt_extra` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'L3 追加区，可空',
  `priority` int(11) NOT NULL DEFAULT 100 COMMENT '同 Agent 内优先级，越小越优先',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_model_instance_id`(`model_instance_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 25 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 模型绑定' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_route
-- ----------------------------
DROP TABLE IF EXISTS `agent_route`;
CREATE TABLE `agent_route`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_agent_id` bigint(20) NOT NULL COMMENT '父 agent_def.id（须为 ROUTER）',
  `child_agent_id` bigint(20) NOT NULL COMMENT '子 agent_def.id（建议 REACT）',
  `route_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '路由键/意图标签，可空则靠父 system_prompt 模型路由',
  `intent` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '意图说明（展示/规则匹配辅助）',
  `sort_order` int(11) NOT NULL DEFAULT 0 COMMENT '排序，越小越靠前',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_parent_agent_id`(`parent_agent_id`) USING BTREE,
  INDEX `idx_child_agent_id`(`child_agent_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Router 子 Agent 路由配置' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_run_event
-- ----------------------------
DROP TABLE IF EXISTS `agent_run_event`;
CREATE TABLE `agent_run_event`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `session_id` bigint(20) NOT NULL COMMENT 'agent_run_session.id',
  `seq` int(11) NOT NULL COMMENT '会话内序号，从 1 递增',
  `event_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'user / assistant / tool / route / system / error',
  `summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '步骤摘要或截断正文；禁止密钥',
  `content_text` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '展示正文（助手Markdown/推理/工具结果摘要）；禁止密钥',
  `token_prompt` int(11) NULL DEFAULT NULL COMMENT '可选 prompt token',
  `token_completion` int(11) NULL DEFAULT NULL COMMENT '可选 completion token',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE,
  INDEX `idx_session_seq`(`session_id`, `seq`) USING BTREE,
  INDEX `idx_event_type`(`event_type`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1098 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 试跑步骤事件摘要' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_run_session
-- ----------------------------
DROP TABLE IF EXISTS `agent_run_session`;
CREATE TABLE `agent_run_session`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id（开跑时）',
  `run_mode` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '开跑时快照：REACT / ROUTER',
  `user_id` bigint(20) NOT NULL COMMENT '平台用户 sys_user.id',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUCCESS / FAILED / CANCELLED',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `message_count` int(11) NOT NULL DEFAULT 0 COMMENT '消息/轮次计数（可选维护）',
  `session_title` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '会话标题：异步生成或用户手改；NULL/空=尚未生成',
  `title_source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT 'AUTO' COMMENT '标题来源：AUTO 自动生成 / MANUAL 用户手改（禁止被自动覆盖）',
  `last_msg_preview` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '最近一条消息短预览；禁止密钥',
  `error_summary` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '失败摘要；禁止密钥/堆栈全文',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID（通常=user_id）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_start_time`(`start_time`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 202 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 试跑会话元数据' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_skill_bind
-- ----------------------------
DROP TABLE IF EXISTS `agent_skill_bind`;
CREATE TABLE `agent_skill_bind`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id',
  `skill_id` bigint(20) NOT NULL COMMENT 'skill_def.id',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_skill_id`(`skill_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent Skill 绑定' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for agent_tool_bind
-- ----------------------------
DROP TABLE IF EXISTS `agent_tool_bind`;
CREATE TABLE `agent_tool_bind`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id',
  `tool_item_id` bigint(20) NOT NULL COMMENT 'tool_item.id',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_tool_item_id`(`tool_item_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 28 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 工具绑定' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for billing_price_rule
-- ----------------------------
DROP TABLE IF EXISTS `billing_price_rule`;
CREATE TABLE `billing_price_rule`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT '空=默认规则',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  `price_prompt` decimal(12, 6) NOT NULL DEFAULT 0.000000,
  `price_completion` decimal(12, 6) NOT NULL DEFAULT 0.000000,
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CNY',
  `effective_from` datetime NOT NULL,
  `effective_to` datetime NULL DEFAULT NULL,
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_model_instance_id`(`model_instance_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Token 单价规则' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for billing_token_daily
-- ----------------------------
DROP TABLE IF EXISTS `billing_token_daily`;
CREATE TABLE `billing_token_daily`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_date` date NOT NULL COMMENT '业务日',
  `agent_id` bigint(20) NULL DEFAULT NULL,
  `model_instance_id` bigint(20) NULL DEFAULT NULL,
  `app_id` bigint(20) NULL DEFAULT NULL COMMENT '一期可空',
  `channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CONSOLE',
  `token_prompt` bigint(20) NOT NULL DEFAULT 0,
  `token_completion` bigint(20) NOT NULL DEFAULT 0,
  `token_total` bigint(20) NOT NULL DEFAULT 0,
  `amount` decimal(14, 4) NULL DEFAULT NULL,
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_biz_date`(`biz_date`) USING BTREE,
  INDEX `idx_agent_model`(`agent_id`, `model_instance_id`) USING BTREE,
  INDEX `idx_app_id`(`app_id`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 21 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Token 日汇总' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for billing_token_ledger
-- ----------------------------
DROP TABLE IF EXISTS `billing_token_ledger`;
CREATE TABLE `billing_token_ledger`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `biz_date` date NOT NULL COMMENT '业务日（Asia/Shanghai）',
  `agent_id` bigint(20) NULL DEFAULT NULL COMMENT 'agent_def.id',
  `model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT 'model_instance.id',
  `app_id` bigint(20) NULL DEFAULT NULL COMMENT 'sys_app.id；一期可空，授权补全后再强约束',
  `session_id` bigint(20) NULL DEFAULT NULL COMMENT '可选关联 agent_run_session.id',
  `channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CONSOLE' COMMENT 'CONSOLE / APP_API',
  `token_prompt` int(11) NOT NULL DEFAULT 0 COMMENT 'prompt tokens',
  `token_completion` int(11) NOT NULL DEFAULT 0 COMMENT 'completion tokens',
  `token_total` int(11) NOT NULL DEFAULT 0 COMMENT '合计',
  `unit_price_prompt` decimal(12, 6) NULL DEFAULT NULL COMMENT '可选单价快照',
  `unit_price_completion` decimal(12, 6) NULL DEFAULT NULL COMMENT '可选单价快照',
  `amount` decimal(14, 4) NULL DEFAULT NULL COMMENT '可选金额',
  `currency` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '如 CNY/USD',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0作废',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_biz_date`(`biz_date`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_model_instance_id`(`model_instance_id`) USING BTREE,
  INDEX `idx_app_id`(`app_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE,
  INDEX `idx_channel`(`channel`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 24113 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Token 计量流水' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for memory_agent_policy
-- ----------------------------
DROP TABLE IF EXISTS `memory_agent_policy`;
CREATE TABLE `memory_agent_policy`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `agent_id` bigint(20) NOT NULL COMMENT 'agent_def.id',
  `enable_short` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1开 0关',
  `enable_long` tinyint(4) NOT NULL DEFAULT 0,
  `max_short_items` int(11) NOT NULL DEFAULT 20,
  `max_long_items` int(11) NOT NULL DEFAULT 100,
  `ctx_max_turns` int(11) NOT NULL DEFAULT 20 COMMENT '上下文滑动窗口最大轮数',
  `ctx_max_chars` int(11) NOT NULL DEFAULT 32000 COMMENT '上下文窗口最大字符',
  `ctx_idle_ttl_sec` int(11) NOT NULL DEFAULT 7200 COMMENT '上下文 Redis idle TTL 秒',
  `short_default_ttl_sec` int(11) NOT NULL DEFAULT 86400 COMMENT 'SHORT 无 expired_time 时默认 TTL 秒',
  `summary_model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT '会话滚动摘要用模型实例；空=平台默认 SUMMARY 用途',
  `enable_long_extract` tinyint(4) NOT NULL DEFAULT 0 COMMENT '1=允许模型批抽长期偏好；平台默认关，显式记住另受 long_extract_on_explicit',
  `long_extract_model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT '长期偏好抽取模型；空=同摘要模型/平台默认',
  `long_extract_on_session_end` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=会话结束触发抽取',
  `long_extract_on_explicit` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=用户明确「记住」类意图立即抽取',
  `status` tinyint(4) NOT NULL DEFAULT 1,
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Agent 记忆与上下文窗口策略' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for memory_item
-- ----------------------------
DROP TABLE IF EXISTS `memory_item`;
CREATE TABLE `memory_item`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `agent_id` bigint(20) NULL DEFAULT NULL COMMENT 'agent_def.id；可空表示全局策略记忆',
  `user_id` bigint(20) NULL DEFAULT NULL COMMENT '平台用户；控制台场景',
  `app_id` bigint(20) NULL DEFAULT NULL COMMENT '预留应用维度',
  `session_id` bigint(20) NULL DEFAULT NULL COMMENT '短期记忆关联试跑/会话',
  `scope` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'SHORT / LONG / AGENT',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `fact_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'LONG 稳定事实键；同 agent+user+scope 合并更新；SHORT/AGENT 可空',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '记忆正文；禁密钥',
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '去重可选',
  `importance` int(11) NOT NULL DEFAULT 0 COMMENT '0-100 可选',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `expired_time` datetime NULL DEFAULT NULL COMMENT '过期时间；SHORT 常用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE,
  INDEX `idx_scope`(`scope`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_expired_time`(`expired_time`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE,
  INDEX `idx_long_fact`(`scope`, `agent_id`, `user_id`, `fact_key`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1776 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '记忆条目' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for memory_session_extract
-- ----------------------------
DROP TABLE IF EXISTS `memory_session_extract`;
CREATE TABLE `memory_session_extract`  (
  `session_id` bigint(20) NOT NULL COMMENT 'agent_run_session.id',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'DONE / FAILED / SKIPPED（均视为已处理，禁止重试出站）',
  `reason` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '跳过或失败原因码；禁止密钥',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后写入时间',
  PRIMARY KEY (`session_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '会话级 LONG 批抽幂等标记' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for model_credential
-- ----------------------------
DROP TABLE IF EXISTS `model_credential`;
CREATE TABLE `model_credential`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider_id` bigint(20) NOT NULL COMMENT 'model_provider.id',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '凭证名，如主Key/备用',
  `api_key_cipher` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'AEAD密文，禁止明文',
  `api_key_mask` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '掩码，如 sk-****abcd',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_provider_id`(`provider_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '模型供应商凭证' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for model_instance
-- ----------------------------
DROP TABLE IF EXISTS `model_instance`;
CREATE TABLE `model_instance`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `provider_id` bigint(20) NOT NULL COMMENT 'model_provider.id（冗余便于过滤）',
  `credential_id` bigint(20) NOT NULL COMMENT 'model_credential.id',
  `display_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '控制台展示名',
  `model_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '厂商模型ID，如 qwen-plus',
  `capability` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'CHAT/ROUTER/REWRITE/SUMMARY/MEMORY/EMBEDDING/RERANK/VISION/ASR/TTS/GUARDRAIL',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `extra_json` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '可选扩展JSON',
  `last_test_time` datetime NULL DEFAULT NULL COMMENT '最后连通测试时间',
  `last_test_ok` tinyint(4) NULL DEFAULT NULL COMMENT '1成功 0失败 空未测',
  `last_test_msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '最近测试摘要，禁含Key',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_provider_id`(`provider_id`) USING BTREE,
  INDEX `idx_credential_id`(`credential_id`) USING BTREE,
  INDEX `idx_capability`(`capability`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 16 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '模型实例' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for model_provider
-- ----------------------------
DROP TABLE IF EXISTS `model_provider`;
CREATE TABLE `model_provider`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名，如通义千问生产',
  `protocol` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'OPENAI_COMPAT/DASHSCOPE/ANTHROPIC/CUSTOM',
  `base_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API Base URL',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_protocol`(`protocol`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '模型供应商' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for obs_span
-- ----------------------------
DROP TABLE IF EXISTS `obs_span`;
CREATE TABLE `obs_span`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'obs_trace.trace_id',
  `parent_id` bigint(20) NULL DEFAULT NULL COMMENT '父行 id；根为空；树关联用',
  `step_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '展示步骤号 1/2/3.1…；仅并行分叉带点号',
  `seq` int(11) NOT NULL COMMENT '同一 trace 内执行序，从 1 递增',
  `span_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'ROUTE/AGENT/LLM/TOOL/RETRIEVE/SYSTEM/TASK',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名：judge、子Agent名、工具名、模型步骤名等',
  `agent_id` bigint(20) NULL DEFAULT NULL COMMENT '本步所属 Agent',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'OK' COMMENT 'RUNNING / OK / ERROR / CANCELLED',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `latency_ms` int(11) NULL DEFAULT NULL COMMENT '本步耗时毫秒',
  `input_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '输入全文；打码；LLM=提示/消息 TOOL=入参',
  `output_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '输出全文；打码；LLM=模型返回 TOOL=出参',
  `error_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '本步失败摘要；打码',
  `model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT 'LLM：模型实例 id',
  `model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'LLM：模型名快照',
  `provider` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'LLM：厂商/协议标识',
  `token_prompt` int(11) NULL DEFAULT NULL COMMENT 'LLM：prompt tokens（展示，不计价）',
  `token_completion` int(11) NULL DEFAULT NULL COMMENT 'LLM：completion tokens',
  `token_total` int(11) NULL DEFAULT NULL COMMENT 'LLM：total tokens',
  `finish_reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'LLM：stop/tool_calls/length/error 等',
  `billing_ledger_id` bigint(20) NULL DEFAULT NULL COMMENT '可选关联 billing_token_ledger.id；金额仍以计费为准',
  `temperature` decimal(8, 4) NULL DEFAULT NULL COMMENT 'LLM：采样温度快照',
  `max_tokens` int(11) NULL DEFAULT NULL COMMENT 'LLM：请求 max_tokens 快照',
  `tool_id` bigint(20) NULL DEFAULT NULL COMMENT 'TOOL：tool_item.id',
  `tool_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'TOOL：调用名快照',
  `tool_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'TOOL：BUILTIN/MCP/HTTP 等',
  `tool_call_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'TOOL：协议 call id',
  `retry_count` int(11) NULL DEFAULT 0 COMMENT 'TOOL：重试次数',
  `tool_success` tinyint(4) NULL DEFAULT NULL COMMENT 'TOOL：1成功 0失败',
  `route_decision` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'ROUTE：决策摘要',
  `child_agent_id` bigint(20) NULL DEFAULT NULL COMMENT 'ROUTE：选中子 Agent',
  `retrieve_query` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'RETRIEVE：检索 query',
  `retrieve_hit_count` int(11) NULL DEFAULT NULL COMMENT 'RETRIEVE：命中条数',
  `retrieve_top_k` int(11) NULL DEFAULT NULL COMMENT 'RETRIEVE：topK',
  `retrieve_kb_id` bigint(20) NULL DEFAULT NULL COMMENT 'RETRIEVE：知识库 id',
  `attrs_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '扩展 JSON 字符串；禁密钥明文',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '旧正文兼容列；新埋点可空，以 input/output 为准',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_trace_seq`(`trace_id`, `seq`) USING BTREE,
  INDEX `idx_span_type`(`span_type`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_model_instance_id`(`model_instance_id`) USING BTREE,
  INDEX `idx_tool_id`(`tool_id`) USING BTREE,
  INDEX `idx_tool_name`(`tool_name`) USING BTREE,
  INDEX `idx_billing_ledger_id`(`billing_ledger_id`) USING BTREE,
  INDEX `idx_parent_id`(`parent_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1009 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Trace Span：执行链路一步（含路由树）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for obs_trace
-- ----------------------------
DROP TABLE IF EXISTS `obs_trace`;
CREATE TABLE `obs_trace`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务 traceId，全局唯一',
  `session_id` bigint(20) NOT NULL COMMENT 'agent_run_session.id；对话场景必填',
  `agent_id` bigint(20) NULL DEFAULT NULL COMMENT '开跑 Agent；ROUTER 则为路由 Agent',
  `user_id` bigint(20) NOT NULL COMMENT '平台用户；admin 看全部，其它只看自己',
  `app_id` bigint(20) NULL DEFAULT NULL COMMENT '应用 id；试跑可空',
  `channel` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'CONSOLE' COMMENT 'CONSOLE / APP_API',
  `title` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '用户问题全文；打码；中栏展示与模糊搜',
  `workflow_run_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '编排：同一次运行 id；对话可空',
  `task_node_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '编排：节点 id/key；对话可空',
  `root_trace_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '编排：根 trace；对话可空',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'RUNNING / OK / ERROR / CANCELLED；创建时 RUNNING',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '结束时间',
  `latency_ms` int(11) NULL DEFAULT NULL COMMENT '整次耗时毫秒',
  `error_summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '整次失败摘要；打码',
  `round_count` int(11) NULL DEFAULT NULL COMMENT 'ReAct 轮数',
  `llm_call_count` int(11) NULL DEFAULT NULL COMMENT '本 trace 内 LLM 步数',
  `tool_call_count` int(11) NULL DEFAULT NULL COMMENT '本 trace 内 TOOL 步数',
  `retrieve_call_count` int(11) NULL DEFAULT NULL COMMENT '本 trace 内 RETRIEVE 步数',
  `token_prompt` int(11) NULL DEFAULT NULL COMMENT '本提问 prompt tokens 汇总（展示，不计价）',
  `token_completion` int(11) NULL DEFAULT NULL COMMENT '本提问 completion tokens 汇总',
  `token_total` int(11) NULL DEFAULT NULL COMMENT '本提问 token 合计',
  `primary_model_instance_id` bigint(20) NULL DEFAULT NULL COMMENT '主模型实例 id',
  `primary_model_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '主模型名快照',
  `finish_reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'completed/cancelled/timeout/max_rounds/error 等',
  `attrs_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '扩展 JSON 字符串；禁密钥明文',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_trace_id`(`trace_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id`) USING BTREE,
  INDEX `idx_agent_id`(`agent_id`) USING BTREE,
  INDEX `idx_start_time`(`start_time`) USING BTREE,
  INDEX `idx_channel`(`channel`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_workflow_run_id`(`workflow_run_id`) USING BTREE,
  INDEX `idx_task_node_id`(`task_node_id`) USING BTREE,
  INDEX `idx_root_trace_id`(`root_trace_id`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 279 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '调用 Trace：一次用户提问' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for prompt_global_constraint
-- ----------------------------
DROP TABLE IF EXISTS `prompt_global_constraint`;
CREATE TABLE `prompt_global_constraint`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `locale` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'zh-CN' COMMENT '多语言预留；当前统一 zh-CN',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '全局红线/语言/脱敏等',
  `version` int(11) NOT NULL DEFAULT 1 COMMENT '版本号',
  `status` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布',
  `is_active` tinyint(4) NOT NULL DEFAULT 0 COMMENT '同 locale 下当前生效=1；每 locale 至多一条生效（应用层保证）',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `create_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人',
  `update_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_locale_ver`(`locale`, `version`) USING BTREE,
  INDEX `idx_locale_active`(`locale`, `is_active`, `deleted`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE,
  INDEX `idx_locale_active_status`(`locale`, `is_active`, `status`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提示词 L1 平台约束' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for prompt_template
-- ----------------------------
DROP TABLE IF EXISTS `prompt_template`;
CREATE TABLE `prompt_template`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '如 CHAT_DEFAULT、ROUTER_JUDGE、QUERY_REWRITE',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '模板中文名',
  `purpose` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'CHAT/ROUTER/REWRITE/SUMMARY/MEMORY/...',
  `locale` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'zh-CN' COMMENT '多语言预留；当前统一 zh-CN',
  `protocol` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '空=通用；OPENAI_COMPAT/DASHSCOPE/ANTHROPIC 薄补丁',
  `version` int(11) NOT NULL DEFAULT 1 COMMENT '版本号',
  `status` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0草稿 1已发布',
  `is_active` tinyint(4) NOT NULL DEFAULT 0 COMMENT '同 template_key+locale 当前生效=1',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `remark` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '内部备注，永不注入模型',
  `create_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人',
  `update_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_key_locale_ver`(`template_key`, `locale`, `version`) USING BTREE,
  INDEX `idx_purpose`(`purpose`) USING BTREE,
  INDEX `idx_key_active`(`template_key`, `locale`, `is_active`, `deleted`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE,
  INDEX `idx_purpose_active`(`purpose`, `locale`, `is_active`, `status`, `deleted`) USING BTREE,
  INDEX `idx_key_ver_deleted`(`template_key`, `locale`, `version`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提示词 L2 用途模板' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for prompt_template_segment
-- ----------------------------
DROP TABLE IF EXISTS `prompt_template_segment`;
CREATE TABLE `prompt_template_segment`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_id` bigint(20) NOT NULL COMMENT 'prompt_template.id',
  `seg_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'role/output_contract/routing_rules/...',
  `display_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'L3 表单 Label，如「角色设定」',
  `sort_no` int(11) NOT NULL DEFAULT 0 COMMENT '组装与展示顺序，越小越前',
  `mutable` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0=core锁定 1=soft可被L3覆盖',
  `required` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1=缺段组装失败',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '段落正文',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_tpl_seg`(`template_id`, `seg_key`) USING BTREE,
  INDEX `idx_template_id`(`template_id`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE,
  INDEX `idx_tpl_sort`(`template_id`, `sort_no`, `deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 14 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '提示词 L2 模板段落' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rag_chunk
-- ----------------------------
DROP TABLE IF EXISTS `rag_chunk`;
CREATE TABLE `rag_chunk`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键；同时作为 Milvus 主键 id',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户',
  `kb_id` bigint(20) NOT NULL COMMENT 'rag_knowledge_base.id',
  `doc_id` bigint(20) NOT NULL COMMENT 'rag_document.id',
  `chunk_index` int(11) NOT NULL COMMENT '文档内切片序号，从 0 起',
  `parent_chunk_id` bigint(20) NULL DEFAULT NULL COMMENT '父块（条目型/超长再切）',
  `part_index` int(11) NULL DEFAULT NULL COMMENT '超长切分序号，从 1 起',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '切片原文（完整正文以本列为准）',
  `token_est` int(11) NULL DEFAULT NULL COMMENT '估算 token 数',
  `milvus_pk` bigint(20) NULL DEFAULT NULL COMMENT 'Milvus 主键，通常等于 id',
  `version` int(11) NOT NULL DEFAULT 1 COMMENT '重嵌递增',
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '去重/变更检测',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0正常 1软删；检索强制 deleted=0',
  `effective_from` bigint(20) NOT NULL DEFAULT 0 COMMENT 'epoch 秒，0=立即',
  `expire_time` bigint(20) NOT NULL DEFAULT 0 COMMENT 'epoch 秒，0=永不过期',
  `create_by` bigint(20) NULL DEFAULT NULL COMMENT '上传人用户ID',
  `owner_user_id` bigint(20) NULL DEFAULT NULL COMMENT '可选归属；检索默认不按人过滤',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb_doc`(`kb_id`, `doc_id`) USING BTREE,
  INDEX `idx_doc_chunk`(`doc_id`, `chunk_index`) USING BTREE,
  FULLTEXT INDEX `ft_rag_chunk_content`(`content`) WITH PARSER `ngram`
) ENGINE = InnoDB AUTO_INCREMENT = 123 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 切片表（② 使用）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rag_document
-- ----------------------------
DROP TABLE IF EXISTS `rag_document`;
CREATE TABLE `rag_document`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户',
  `kb_id` bigint(20) NOT NULL COMMENT 'rag_knowledge_base.id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题（通常为文件名）',
  `source_uri` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '原文件路径或对象存储地址',
  `mime` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'MIME 或扩展名',
  `byte_size` bigint(20) NULL DEFAULT NULL COMMENT '原文件字节数',
  `content_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '原文件 SHA-256，用于重灌去重',
  `chunk_count` int(11) NOT NULL DEFAULT 0 COMMENT '切片数',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / RUNNING / READY / FAILED',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '失败原因',
  `version` int(11) NOT NULL DEFAULT 1 COMMENT '重灌递增',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '上传人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb_status`(`kb_id`, `status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 34 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 文档表（② 使用）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rag_ingest_task
-- ----------------------------
DROP TABLE IF EXISTS `rag_ingest_task`;
CREATE TABLE `rag_ingest_task`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户',
  `kb_id` bigint(20) NOT NULL COMMENT 'rag_knowledge_base.id',
  `doc_id` bigint(20) NOT NULL COMMENT 'rag_document.id',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `stage` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '当前阶段：解析/切片/向量化/写库',
  `progress` int(11) NOT NULL DEFAULT 0 COMMENT '0-100',
  `attempts` int(11) NOT NULL DEFAULT 0 COMMENT '已尝试次数，重试 +1',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '失败原因；成功时清空',
  `start_time` datetime NULL DEFAULT NULL COMMENT '本轮开始时间',
  `finish_time` datetime NULL DEFAULT NULL COMMENT '本轮结束时间',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '触发人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_doc`(`doc_id`) USING BTREE,
  INDEX `idx_kb_status`(`kb_id`, `status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 34 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 异步入库任务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rag_kb_grant
-- ----------------------------
DROP TABLE IF EXISTS `rag_kb_grant`;
CREATE TABLE `rag_kb_grant`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户',
  `kb_id` bigint(20) NOT NULL COMMENT 'rag_knowledge_base.id',
  `grant_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'USER / DEPT / ROLE',
  `grant_target_id` bigint(20) NOT NULL COMMENT '被授权对象 id',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb`(`kb_id`) USING BTREE,
  INDEX `idx_target`(`grant_type`, `grant_target_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 知识库授权（二期启用）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for rag_knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `rag_knowledge_base`;
CREATE TABLE `rag_knowledge_base`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '租户；一期恒为 0',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '知识库名称',
  `description` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '描述',
  `store_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'LOCAL_MILVUS；DIFY_API/BAILIAN_API 预留',
  `collection_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '实际 Collection 名（系统生成）',
  `store_capability` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'DENSE_BM25 / DENSE_ONLY；建集后回填',
  `embedding_model_id` bigint(20) NOT NULL COMMENT 'model_instance.id，用途必须为 EMBEDDING',
  `vector_dimension` int(11) NOT NULL COMMENT '向量维度，建库后锁定',
  `rerank_model_id` bigint(20) NULL DEFAULT NULL COMMENT '可选，model_instance.id，用途 RERANK',
  `isolation_mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PHYSICAL' COMMENT 'PHYSICAL / LOGICAL / PUBLIC',
  `visibility` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PRIVATE' COMMENT 'PUBLIC / RESTRICTED / PRIVATE',
  `retrieval_config` json NULL COMMENT 'topK、RRF k、改写/Rerank 开关等默认检索参数',
  `ingest_config` json NULL COMMENT 'chunk_size / overlap 等入库参数（② 使用）',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'INIT_FAILED' COMMENT 'READY / INIT_FAILED / DISABLED',
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '初始化失败原因（已完成初始化时清空）',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_tenant_name`(`tenant_id`, `name`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 6 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'RAG 知识库主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for skill_def
-- ----------------------------
DROP TABLE IF EXISTS `skill_def`;
CREATE TABLE `skill_def`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '调用名/唯一业务键（未删范围内唯一，应用层校验）',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示标题',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '简介，卡片摘要',
  `trigger_keywords` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '触发关键词，逗号分隔，命中则强制注入 Prompt',
  `content_md` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'Skill 正文 Markdown（一期存全文）',
  `version` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '版本号字符串；一期不做多版本表',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_name`(`name`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'Skill 定义（一期单表 skill_def）' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_app
-- ----------------------------
DROP TABLE IF EXISTS `sys_app`;
CREATE TABLE `sys_app`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `app_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '对外 appId',
  `app_secret_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密钥哈希，勿存明文',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `dept_id` bigint(20) NULL DEFAULT NULL COMMENT '归属部门，列表隔离用',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_app_key`(`app_key`) USING BTREE,
  INDEX `idx_dept`(`dept_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '调用方应用' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_app_agent
-- ----------------------------
DROP TABLE IF EXISTS `sys_app_agent`;
CREATE TABLE `sys_app_agent`  (
  `app_id` bigint(20) NOT NULL,
  `agent_id` bigint(20) NOT NULL COMMENT '关联 agent 业务表主键',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`app_id`, `agent_id`) USING BTREE,
  INDEX `idx_agent`(`agent_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '应用-Agent授权' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dept
-- ----------------------------
DROP TABLE IF EXISTS `sys_dept`;
CREATE TABLE `sys_dept`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '部门ID',
  `parent_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '父部门ID，0=根',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '部门名称',
  `path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT '祖先路径，逗号分隔，如 0,1,3，便于查本部门及以下',
  `sort` int(11) NOT NULL DEFAULT 0 COMMENT '显示顺序，越小越靠前',
  `leader_user_id` bigint(20) NULL DEFAULT NULL COMMENT '负责人用户ID，可空',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '联系电话',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_parent`(`parent_id`) USING BTREE,
  INDEX `idx_path`(`path`(191)) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '部门' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_login_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NULL DEFAULT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `login_type` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1密码 2SSO 3apiKey',
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `user_agent` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `success` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `message` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_time`(`user_id`, `create_time`) USING BTREE,
  INDEX `idx_username_time`(`username`, `create_time`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 342 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '登录日志' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) NOT NULL DEFAULT 0 COMMENT '父节点，0=根',
  `type` tinyint(4) NOT NULL COMMENT '1目录 2菜单 3按钮 4目录无折叠',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '名称',
  `path` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '路由 path',
  `component` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '前端组件',
  `redirect` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '目录重定向',
  `permission` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '权限码，如 agent:create；按钮必填',
  `icon` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `sort` int(11) NOT NULL DEFAULT 0,
  `visible` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏（路由仍可在）',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `open_type` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0内页 1外链',
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_parent`(`parent_id`) USING BTREE,
  INDEX `idx_permission`(`permission`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 110 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '菜单与权限码' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_oper_log
-- ----------------------------
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE `sys_oper_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NULL DEFAULT NULL,
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `dept_id` bigint(20) NULL DEFAULT NULL,
  `module` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '' COMMENT 'user/role/agent/model/tool/app',
  `action` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'create/update/delete/grant/enable/disable',
  `target_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '资源类型',
  `target_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '资源ID',
  `request_method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `success` tinyint(4) NOT NULL DEFAULT 1,
  `error_msg` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `detail_json` json NULL COMMENT '变更摘要，禁止存密码/大报文',
  `cost_ms` int(11) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_time`(`user_id`, `create_time`) USING BTREE,
  INDEX `idx_module_time`(`module`, `create_time`) USING BTREE,
  INDEX `idx_target`(`target_type`, `target_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1078 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '平台操作日志' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色编码，如 admin/developer/viewer',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色名称',
  `data_scope` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1仅本人 2本部门 3本部门及以下 4全部 5自定义部门',
  `sort` int(11) NOT NULL DEFAULT 0,
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_code`(`code`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 17 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '平台角色' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_dept
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_dept`;
CREATE TABLE `sys_role_dept`  (
  `role_id` bigint(20) NOT NULL,
  `dept_id` bigint(20) NOT NULL,
  PRIMARY KEY (`role_id`, `dept_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色-自定义部门范围' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu`  (
  `role_id` bigint(20) NOT NULL,
  `menu_id` bigint(20) NOT NULL,
  PRIMARY KEY (`role_id`, `menu_id`) USING BTREE,
  INDEX `idx_menu`(`menu_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '角色-菜单' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `dept_id` bigint(20) NULL DEFAULT NULL COMMENT '所属部门',
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '登录名',
  `password_hash` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'BCrypt密文',
  `nickname` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '显示名',
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `avatar_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `gender` tinyint(4) NULL DEFAULT 0 COMMENT '0未知 1男 2女',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用',
  `is_admin` tinyint(4) NOT NULL DEFAULT 0 COMMENT '1超级管理员，跳过部分校验',
  `last_login_time` datetime NULL DEFAULT NULL COMMENT '最近登录时间',
  `last_login_ip` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `deleted` tinyint(4) NOT NULL DEFAULT 0,
  `created_by` bigint(20) NULL DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username`) USING BTREE,
  INDEX `idx_dept`(`dept_id`) USING BTREE,
  INDEX `idx_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 57 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '平台用户' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role`  (
  `user_id` bigint(20) NOT NULL,
  `role_id` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`, `role_id`) USING BTREE,
  INDEX `idx_role`(`role_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户-角色' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for tool_item
-- ----------------------------
DROP TABLE IF EXISTS `tool_item`;
CREATE TABLE `tool_item`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `source_id` bigint(20) NOT NULL COMMENT 'tool_source.id',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '工具名（调用名）',
  `title` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '展示名可选',
  `description` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '说明',
  `input_schema_json` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'JSON schema 原文或摘要',
  `status` tinyint(4) NOT NULL DEFAULT 0 COMMENT '1正常 0停用；MCP 新同步默认 0，BUILTIN 种子可 1',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_source_id`(`source_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE,
  INDEX `idx_name`(`name`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 254 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工具项' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for tool_source
-- ----------------------------
DROP TABLE IF EXISTS `tool_source`;
CREATE TABLE `tool_source`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '展示名，如内置工具/某 MCP 源',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'BUILTIN / MCP',
  `transport` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'MCP 用：一期 SSE；BUILTIN 可空',
  `base_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'MCP SSE URL',
  `auth_header_cipher` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '可选加密 Header/Token（AEAD），同模型 Key 套路',
  `auth_header_mask` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '掩码展示，如 Bearer ****abcd',
  `status` tinyint(4) NOT NULL DEFAULT 1 COMMENT '1正常 0停用（基座语义）',
  `remark` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `last_sync_time` datetime NULL DEFAULT NULL COMMENT '最近同步时间',
  `last_sync_ok` tinyint(4) NULL DEFAULT NULL COMMENT '1成功 0失败',
  `last_sync_msg` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '同步结果摘要；禁止含密钥',
  `deleted` tinyint(4) NOT NULL DEFAULT 0 COMMENT '0未删 1已删',
  `created_by` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_by` bigint(20) NULL DEFAULT NULL COMMENT '更新人用户ID',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_type`(`type`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE,
  INDEX `idx_deleted`(`deleted`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '工具源' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
