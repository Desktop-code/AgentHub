-- =============================================================================
-- 工作台刀5B：默认接待可把「已发布工作流」作为路由目标（增量 DDL，可重复执行）
-- 目标库：agenthub（MySQL 5.7+，兼容 8.0）
-- 执行方式：手工执行；按 information_schema 判重，重复跑不报「列已存在」
-- 前置：刀5 的 sql/schema-delta-workbench-ma-target.sql 已执行
-- 禁止：DROP 列 / 重建表 / 重写刀5 脚本 / 改动 ma_candidate.intent_desc 的既有口径
--
-- 做三件事：
--   1) ma_candidate.target_type 扩展 WORKFLOW：AGENT=子 Agent / MA=另一套编排方案 /
--      WORKFLOW=已发布工作流（只绑默认 release，草稿不可被路由到）；
--   2) ma_candidate.workflow_def_id：target_type=WORKFLOW 时指向 workflow_def.id；
--   3) workflow_run.workbench_session_id：工作台会话关联，供「人工恢复后把结果追写回同一会话」
--      与「同一会话同一工作流未结束时禁止重复开跑」两条规则使用。试跑 / 应用触发恒为 NULL。
--
-- 说明字段仍单源：工作流候选的意图说明读 workflow_def.description，不在候选上另编一份。
-- =============================================================================
USE agenthub;

-- -----------------------------------------------------------------------------
-- 1) ma_candidate.target_type 扩展枚举值：只改注释与默认值，不重建列
--    存量 AGENT / MA 行原样保留（MODIFY 只改元数据，不写数据）
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND COLUMN_NAME = 'target_type'
);
-- 刀5 脚本未执行（列不存在）时先按刀5 的口径建列，再按本刀扩展注释
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_candidate` ADD COLUMN `target_type` varchar(16) NOT NULL DEFAULT ''AGENT'' COMMENT ''候选目标类型：AGENT=子 Agent / MA=另一套编排方案 / WORKFLOW=已发布工作流'' AFTER `ma_id`',
  'ALTER TABLE `ma_candidate` MODIFY COLUMN `target_type` varchar(16) NOT NULL DEFAULT ''AGENT'' COMMENT ''候选目标类型：AGENT=子 Agent / MA=另一套编排方案 / WORKFLOW=已发布工作流''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2) ma_candidate.workflow_def_id
--    唯一约束仍按 (ma_id, route_key)：同一方案内路由键唯一，与目标类型无关
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND COLUMN_NAME = 'workflow_def_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_candidate` ADD COLUMN `workflow_def_id` bigint(20) NULL DEFAULT NULL COMMENT ''target_type=WORKFLOW 时指向 workflow_def.id；AGENT / MA 恒为 NULL'' AFTER `ma_def_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 反向兜底：声明为 WORKFLOW 却没有目标工作流的脏行不让它参与判路由
UPDATE `ma_candidate` SET `target_type` = 'AGENT'
WHERE `target_type` = 'WORKFLOW' AND `workflow_def_id` IS NULL AND `agent_id` IS NOT NULL;

-- 目标工作流索引：换线 / 路由池回显要按目标批量反查
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND INDEX_NAME = 'idx_ma_candidate_workflow_target'
);
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `ma_candidate` ADD INDEX `idx_ma_candidate_workflow_target` (`workflow_def_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3) workflow_run.workbench_session_id
--    只加可空列：试跑 / 应用触发保持 NULL，既有观测口径（根 trace session_id=0 等）不变
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_run'
    AND COLUMN_NAME = 'workbench_session_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `workflow_run` ADD COLUMN `workbench_session_id` bigint(20) NULL DEFAULT NULL COMMENT ''工作台会话 agent_run_session.id；试跑 / 应用触发为 NULL'' AFTER `client_request_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 未结束 run 的防重查：同会话同工作流只允许一条 RUNNING / PAUSED_HUMAN
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_run'
    AND INDEX_NAME = 'idx_workflow_run_workbench_session'
);
-- 注意：MySQL 5.7 的索引前缀长度限制与本列无关；普通二级索引即可
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `workflow_run` ADD INDEX `idx_workflow_run_workbench_session` (`workbench_session_id`, `workflow_id`, `status`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 4) workflow_run.trigger_type 注释补 WORKBENCH（只改元数据，不动数据与默认值）
--    存量值仍是 TRIAL / APP / API / MANUAL；本刀新增的只有 WORKBENCH
-- -----------------------------------------------------------------------------
ALTER TABLE `workflow_run`
  MODIFY COLUMN `trigger_type` varchar(32) NOT NULL DEFAULT 'TRIAL'
  COMMENT 'TRIAL/APP/API/MANUAL/WORKBENCH；WORKBENCH=工作台默认接待命中';

-- -----------------------------------------------------------------------------
-- 自检 1：应看到 workflow_def_id 列、target_type 注释含 WORKFLOW、workbench_session_id 列
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'agenthub' AND TABLE_NAME = 'ma_candidate'
  AND COLUMN_NAME IN ('target_type', 'ma_def_id', 'workflow_def_id', 'agent_id');

SHOW INDEX FROM `ma_candidate` WHERE Key_name = 'idx_ma_candidate_workflow_target';

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'agenthub' AND TABLE_NAME = 'workflow_run'
  AND COLUMN_NAME = 'workbench_session_id';

-- -----------------------------------------------------------------------------
-- 自检 2：默认接待当前的候选类型分布（加入工作流后应出现 WORKFLOW 行）
-- -----------------------------------------------------------------------------
SELECT m.`code` AS scheme_code, c.`target_type`, c.`route_key`,
       c.`agent_id`, c.`ma_def_id`, c.`workflow_def_id`, c.`sort_no`, c.`enabled`
FROM `ma_candidate` c
JOIN `ma_def` m ON m.`id` = c.`ma_id`
WHERE m.`is_reception_default` = 1
ORDER BY c.`sort_no` ASC, c.`id` ASC;

-- -----------------------------------------------------------------------------
-- 自检 3：池内工作流必须「已发布 + 有默认发布指针 + 有说明」（应为空）
-- -----------------------------------------------------------------------------
SELECT c.`id` AS candidate_id, c.`ma_id`, c.`workflow_def_id`,
       w.`status`, w.`default_release_id`, w.`description`
FROM `ma_candidate` c
LEFT JOIN `workflow_def` w ON w.`id` = c.`workflow_def_id`
WHERE c.`target_type` = 'WORKFLOW'
  AND (w.`id` IS NULL
       OR w.`deleted` = 1
       OR w.`status` <> 'HAS_RELEASE'
       OR w.`default_release_id` IS NULL
       OR w.`description` IS NULL
       OR TRIM(w.`description`) = '');

-- -----------------------------------------------------------------------------
-- 自检 4：同一工作台会话同一工作流不应同时有两条未结束 run（应为空）
-- -----------------------------------------------------------------------------
SELECT `workbench_session_id`, `workflow_id`, COUNT(*) AS active_runs
FROM `workflow_run`
WHERE `workbench_session_id` IS NOT NULL
  AND `status` IN ('RUNNING', 'PAUSED_HUMAN')
GROUP BY `workbench_session_id`, `workflow_id`
HAVING COUNT(*) > 1;
