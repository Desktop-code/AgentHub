-- =============================================================================
-- 工作台刀5：默认接待可把另一套 MA 方案作为路由目标（增量 DDL，可重复执行）
-- 目标库：agenthub（MySQL 5.7+，兼容 8.0）
-- 执行方式：手工执行；按 information_schema 判重，重复跑不报「列已存在」
-- 前置：刀1 的 schema-delta-workbench-chat.sql 已执行
-- 禁止：DROP 列 / 重建表 / 改动 ma_candidate.intent_desc 的既有口径
--
-- 做四件事：
--   1) ma_candidate.target_type：候选目标类型，AGENT=子 Agent（存量行），MA=另一套编排方案；
--   2) ma_candidate.ma_def_id：target_type=MA 时指向 ma_def.id；
--   3) ma_candidate.agent_id 放开为可空：MA 候选没有子 Agent；
--   4) ma_def.description：方案说明，作为 MA 候选的意图说明（与 agent_def.description 同语义）。
-- =============================================================================
USE agenthub;

-- -----------------------------------------------------------------------------
-- 1) ma_candidate.target_type
--    默认 AGENT：存量行语义不变，旧代码按 agent_id 读仍然成立
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND COLUMN_NAME = 'target_type'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_candidate` ADD COLUMN `target_type` varchar(16) NOT NULL DEFAULT ''AGENT'' COMMENT ''候选目标类型：AGENT=子 Agent / MA=另一套编排方案'' AFTER `ma_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2) ma_candidate.ma_def_id
--    唯一约束仍按 (ma_id, route_key)：同一方案内路由键唯一，与目标类型无关
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND COLUMN_NAME = 'ma_def_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_candidate` ADD COLUMN `ma_def_id` bigint(20) NULL DEFAULT NULL COMMENT ''target_type=MA 时指向 ma_def.id；AGENT 恒为 NULL'' AFTER `agent_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 3) agent_id 放开为可空（MA 候选没有子 Agent）
--    只改可空性，不动类型与注释宽度；已有值原样保留
-- -----------------------------------------------------------------------------
SET @agent_nullable := (
  SELECT IS_NULLABLE FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND COLUMN_NAME = 'agent_id'
);
SET @ddl := IF(@agent_nullable = 'NO',
  'ALTER TABLE `ma_candidate` MODIFY COLUMN `agent_id` bigint(20) NULL COMMENT ''候选子 agent_def.id；target_type=AGENT 时必填，MA 候选为 NULL''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量行显式回落 AGENT：列默认值已保证，这次 UPDATE 只兜「手工插过未带列」的行
UPDATE `ma_candidate` SET `target_type` = 'AGENT'
WHERE `target_type` IS NULL OR TRIM(`target_type`) = '';

-- 反向兜底：声明为 MA 却没有目标方案的行（脏数据）不让它参与判路由
UPDATE `ma_candidate` SET `target_type` = 'AGENT'
WHERE `target_type` = 'MA' AND `ma_def_id` IS NULL AND `agent_id` IS NOT NULL;

-- 目标方案索引：换线 / 路由池回显要按目标批量反查
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_candidate'
    AND INDEX_NAME = 'idx_ma_candidate_ma_target'
);
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `ma_candidate` ADD INDEX `idx_ma_candidate_ma_target` (`ma_def_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 4) ma_def.description：方案说明（MA 候选的意图说明单一真源）
--    与 agent_def.description 同语义：判路由提示词只读这一列，不在候选上另编一份
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_def'
    AND COLUMN_NAME = 'description'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_def` ADD COLUMN `description` varchar(512) NULL DEFAULT NULL COMMENT ''方案说明：被当作路由目标时的意图说明，与 agent_def.description 同语义'' AFTER `name`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 自检 1：应看到 target_type / ma_def_id 两个新列、agent_id 为 YES、目标索引
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'agenthub' AND TABLE_NAME = 'ma_candidate'
  AND COLUMN_NAME IN ('agent_id', 'target_type', 'ma_def_id');

SHOW INDEX FROM `ma_candidate` WHERE Key_name = 'idx_ma_candidate_ma_target';

-- -----------------------------------------------------------------------------
-- 自检 2：默认接待当前的候选类型分布（应看到 AGENT 行；加入 MA 后出现 MA 行）
-- -----------------------------------------------------------------------------
SELECT m.`code` AS scheme_code, c.`target_type`, c.`route_key`,
       c.`agent_id`, c.`ma_def_id`, c.`sort_no`, c.`enabled`
FROM `ma_candidate` c
JOIN `ma_def` m ON m.`id` = c.`ma_id`
WHERE m.`is_reception_default` = 1
ORDER BY c.`sort_no` ASC, c.`id` ASC;

-- -----------------------------------------------------------------------------
-- 自检 3：循环风险（应为空）：
--   a) 目标方案本身就是默认接待；
--   b) 目标方案自己的候选里还有 MA 候选（下探超过一层）。
-- -----------------------------------------------------------------------------
SELECT c.`id` AS candidate_id, c.`ma_id`, c.`ma_def_id` AS target_ma_id,
       t.`is_reception_default` AS target_is_default
FROM `ma_candidate` c
JOIN `ma_def` t ON t.`id` = c.`ma_def_id`
WHERE c.`target_type` = 'MA' AND t.`is_reception_default` = 1;

SELECT c.`id` AS candidate_id, c.`ma_id`, c.`ma_def_id` AS target_ma_id
FROM `ma_candidate` c
WHERE c.`target_type` = 'MA'
  AND EXISTS (
    SELECT 1 FROM `ma_candidate` inner_c
    WHERE inner_c.`ma_id` = c.`ma_def_id` AND inner_c.`target_type` = 'MA'
  );
