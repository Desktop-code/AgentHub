-- =============================================================================
-- 工作台刀1：默认接待位 + 工作台会话渠道
-- 目标库：agenthub（MySQL 5.7+，兼容 8.0）
-- 执行方式：手工执行，可重复执行（按 information_schema 判重，不报列已存在）
-- 前置：本脚本必须先于 data-init-workbench-reception.sql 执行
-- 禁止：DROP / 重建任何既有表；只做增量列与索引
-- =============================================================================
USE agenthub;

-- -----------------------------------------------------------------------------
-- 1) ma_def：平台默认接待位
-- 说明：
--   - is_reception_default 是业务开关，只允许一条为 1（按租户）；
--   - reception_slot 是生成列，仅当 is_reception_default=1 时取常量 'DEFAULT'，
--     其余为 NULL；MySQL 唯一索引允许多个 NULL，因此天然只锁一条；
--   - 与 model_instance.platform_locked 同一套路，避免应用层「先清后设」漏事务时出现双默认。
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_def'
    AND COLUMN_NAME = 'is_reception_default'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_def` ADD COLUMN `is_reception_default` tinyint(4) NOT NULL DEFAULT 0 COMMENT ''1=平台默认接待方案；唯一索引保证每租户仅一条'' AFTER `enabled`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_def'
    AND COLUMN_NAME = 'reception_slot'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `ma_def` ADD COLUMN `reception_slot` varchar(16) GENERATED ALWAYS AS (IF(`is_reception_default` = 1, ''DEFAULT'', NULL)) STORED COMMENT ''默认接待唯一槽：非默认恒为 NULL'' AFTER `is_reception_default`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ma_def'
    AND INDEX_NAME = 'uk_ma_def_reception'
);
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `ma_def` ADD UNIQUE KEY `uk_ma_def_reception` (`tenant_id`, `reception_slot`) USING BTREE',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 2) agent_run_session：入口渠道与默认接待归属
-- 说明：
--   - entry_channel：WORKBENCH=正式工作台对话；DEBUG=控制台调试（存量行一律回落 DEBUG）；
--     两套列表按该列隔离，互不串会话；
--   - reception_ma_id：建会话时服务端落定的默认接待方案快照，便于追溯；
--   - sticky_route_key / sticky_agent_id：刀3「沿用本次路由 / 手动换线」的列位，
--     本刀只建列，不实现沿用与换线逻辑。
-- -----------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_run_session'
    AND COLUMN_NAME = 'entry_channel'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `agent_run_session` ADD COLUMN `entry_channel` varchar(32) NOT NULL DEFAULT ''DEBUG'' COMMENT ''入口渠道：WORKBENCH 工作台对话 / DEBUG 控制台调试'' AFTER `ma_def_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_run_session'
    AND COLUMN_NAME = 'reception_ma_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `agent_run_session` ADD COLUMN `reception_ma_id` bigint(20) NULL DEFAULT NULL COMMENT ''建会话时的默认接待方案快照 ma_def.id'' AFTER `entry_channel`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_run_session'
    AND COLUMN_NAME = 'sticky_route_key'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `agent_run_session` ADD COLUMN `sticky_route_key` varchar(64) NULL DEFAULT NULL COMMENT ''刀3 路由沿用：上次命中的 route_key；本刀不读写'' AFTER `reception_ma_id`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_run_session'
    AND COLUMN_NAME = 'sticky_agent_id'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `agent_run_session` ADD COLUMN `sticky_agent_id` bigint(20) NULL DEFAULT NULL COMMENT ''刀3 路由沿用：上次命中的子 Agent；本刀不读写'' AFTER `sticky_route_key`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量行显式回落 DEBUG：列默认值已保证，这次 UPDATE 只兜「历史脏值 / 手工插入未带列」
UPDATE `agent_run_session` SET `entry_channel` = 'DEBUG'
WHERE `entry_channel` IS NULL OR `entry_channel` = '';

-- 工作台左栏：按渠道 + 用户 + 开始时间倒序；调试左栏仍走既有 idx 组合
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_run_session'
    AND INDEX_NAME = 'idx_run_session_channel'
);
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE `agent_run_session` ADD INDEX `idx_run_session_channel` (`entry_channel`, `user_id`, `start_time`)',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 自检：应看到 ma_def 两个新列 + 唯一索引，agent_run_session 四个新列 + 渠道索引
-- -----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, EXTRA, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'agenthub' AND TABLE_NAME = 'ma_def'
  AND COLUMN_NAME IN ('is_reception_default', 'reception_slot');

SHOW INDEX FROM `ma_def` WHERE Key_name = 'uk_ma_def_reception';

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'agenthub' AND TABLE_NAME = 'agent_run_session'
  AND COLUMN_NAME IN ('entry_channel', 'reception_ma_id', 'sticky_route_key', 'sticky_agent_id');

SHOW INDEX FROM `agent_run_session` WHERE Key_name = 'idx_run_session_channel';
