-- =============================================================================
-- 刀10：平台级 Embedding / Rerank 解硬编码（模型中心平台锁定 + 维度快照）
-- 库：agenthub（MySQL 5.7+）。禁 DROP、禁重建用户体系表；本脚本只做增量。
-- 可重复执行：加列 / 加索引前查 information_schema，已存在则跳过。
--
-- 与本仓 sql/agenthub.sql 的关系：全量结构里已含本文件新增的列与唯一索引，
-- 本文件只服务「已有数据的存量库」升级；新建空库直接导入 agenthub.sql 即可，不必再跑本文件。
--
-- 语义（交付写死）：
--   platform_locked = 1 表示平台级全局实例；EMBEDDING、RERANK 各最多一条。
--   embedding_dim  = 最近一次连通测试成功快照的向量维度；只给 EMBEDDING 写。
--   platform_slot  = 生成列（仅 platform_locked=1 且 deleted=0 时有值），配合唯一索引
--                    在数据库层兜住「同用途第二条平台级」的并发抢写；NULL 不参与唯一。
--
-- 升级后手动步骤：
--   1) 部署本脚本与代码；
--   2) 到「模型管理」对 Embedding 实例先做连通测试（成功即写 embedding_dim），
--      再点「设为平台级」；Rerank 同理（不要求维度快照）。
--   3) 平台级 Embedding 就绪后，按需触发 POST /api/memory/vector/reconcile。
--      （低版本 Milvus 集合缺 expired_time / importance / content 字段时，还需按
--        docs 的记忆向量 schema v2 说明受控重建，再对有效 LONG 行打回 PENDING 补同步）
-- =============================================================================

use agenthub;

SET @db := DATABASE();

-- 1) platform_locked：是否平台锁定
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE model_instance ADD COLUMN platform_locked tinyint(4) NOT NULL DEFAULT 0 COMMENT ''是否平台锁定：1=平台级全局实例（EMBEDDING/RERANK 各最多一条）'' AFTER status',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'platform_locked'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) embedding_dim：最近一次连通测试成功快照的向量维度（仅 EMBEDDING）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE model_instance ADD COLUMN embedding_dim int(11) NULL DEFAULT NULL COMMENT ''最近一次连通测试成功快照的向量维度（仅 EMBEDDING 用途写）'' AFTER last_test_msg',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'embedding_dim'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) platform_slot：平台级唯一槽（生成列，NULL 不参与唯一）
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE model_instance ADD COLUMN platform_slot varchar(32) GENERATED ALWAYS AS (IF(platform_locked = 1 AND deleted = 0, capability, NULL)) STORED COMMENT ''平台级唯一槽：EMBEDDING/RERANK 各最多一条（NULL 不参与唯一）''',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'model_instance' AND COLUMN_NAME = 'platform_slot'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) 唯一索引：并发下由数据库兜底，应用侧仍先做可读的二次确认与同步中拒绝
SET @sql := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE model_instance ADD UNIQUE INDEX uk_platform_slot (platform_slot)',
    'SELECT 1')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'model_instance' AND INDEX_NAME = 'uk_platform_slot'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5) 权限按钮种子：设平台级（避免已有库只拿到代码没有菜单码）
--    与 sql/data-init.sql 的 133 行同码同父；INSERT IGNORE 保证重复执行不冲突。
INSERT IGNORE INTO sys_menu (id, parent_id, type, name, path, component, redirect, permission, icon, sort, visible, status, open_type, deleted, created_by, create_time, updated_by, update_time)
VALUES (133, 28, 3, '设平台级', NULL, NULL, NULL, 'model:instance:platform', NULL, 10, 1, 1, 0, 0, NULL, NOW(), NULL, NOW());
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 133);
