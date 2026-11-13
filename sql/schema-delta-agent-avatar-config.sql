-- =============================================================================
-- Agent 形象配置聚合为 JSON：agent_def.avatar_config（可重复执行）
-- 目标库：agenthub（MySQL 5.7+）。执行方式：由人工手跑；应用侧不自动建列。
-- 禁止：DROP 列 / 重建表 / 重建用户体系表。
--
-- 存在意义（踩坑记录）：
--   后端 AgentDefEntity 已映射 avatarConfig，MyBatis-Plus 的列表 / 详情查询会显式
--   SELECT avatar_config；库中缺列时每次请求都抛
--   SQLSyntaxErrorException: Unknown column 'avatar_config' in 'field list'。
--   因此本列必须先于新代码部署到存量库。
--
-- 结构：
--   preset：{"type":"preset","icon":"look-guide","color":"#635BFF"}
--   custom：{"type":"custom","url":"/uploads/agent-avatars/12/xxx.png"}
--   未配置：NULL
--
-- 说明：
--   1) 若库里曾存在旧列 agent_def.icon / icon_color，这里仅做一次性回填，
--      之后不再读写这两列；未加过则跳过回填，脚本仍可重复执行。
--   2) 本列与 sys_user.avatar_url 完全分开：Agent 形象不读用户头像。
--   3) 新建空库直接导入 sql/agenthub.sql 即可（全量结构已含本列），无需再跑本文件。
-- =============================================================================
USE agenthub;

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_def'
    AND COLUMN_NAME = 'avatar_config'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE `agent_def` ADD COLUMN `avatar_config` json NULL COMMENT ''形象配置：preset{icon,color} 或 custom{url}'' AFTER `description`',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- -----------------------------------------------------------------------------
-- 一次性回填：旧列 icon / icon_color 存在且有值时，迁到 avatar_config。
-- 两列都在才回填；否则跳过，保证脚本在任意历史状态都可执行。
-- -----------------------------------------------------------------------------
SET @has_legacy := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_def'
    AND COLUMN_NAME IN ('icon', 'icon_color')
);
SET @backfill := IF(@has_legacy = 2,
  'UPDATE `agent_def` SET `avatar_config` = JSON_OBJECT(''type'', ''preset'', ''icon'', `icon`, ''color'', `icon_color`)
     WHERE `avatar_config` IS NULL AND (`icon` IS NOT NULL OR `icon_color` IS NOT NULL)',
  'SELECT 1');
PREPARE stmt2 FROM @backfill;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 自检：应返回 1
SELECT COUNT(*) AS avatar_config_exists FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_def' AND COLUMN_NAME = 'avatar_config';
