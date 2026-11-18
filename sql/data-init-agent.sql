-- =============================================================================
-- AgentHub · 系统运行必需配置（除 sys 外）
-- 文件：sql/data-init-agent.sql
-- 目标库：agenthub（MySQL 5.7+；禁用 8.0-only 语法）
--
-- 【新库正式入口 · 三份】
--   1) sql/agenthub.sql            建表（纯 DDL，已含全部结构增量）
--   2) sql/data-init.sql           sys / 平台基础（用户、角色、菜单、工具、L1、5 条模板头）
--   3) sql/data-init-agent.sql     本文件：缺了工作台就跑不起来的非 sys 配置
--   测试 / 演示另跑（**不是**新库必跑）：sql/data-init-test-tmp.sql
--
-- 【本文件只装「运行必需」，逐项给出缺了会怎样】
--   A. 提示词 L2 段落（CHAT / ROUTER / MEMORY 为阻断项，SUMMARY / REWRITE 为平台目录项）
--      - 缺 CHAT 段：任何对话 Agent 组装提示词直接 PROMPT_UNAVAILABLE，工作台发不出第一句；
--      - 缺 ROUTER 段：入口 Router 组装失败，多智能体/工作台路由不可用；
--      - 缺 MEMORY 锁定段：长期记忆抽取 assembleLockedByPurpose 抛错（长记忆总闸默认开）；
--      - SUMMARY / REWRITE：运行期暂不由 L2 模板取正文（滚动摘要走内置文案），
--        但它们是平台提示词目录的固定用途槽，缺了「提示词模板库」对应左栏一片空白；
--        属平台配置而非测试数据，故留在本文件，注释与文档都标明「非阻断」。
--   B. 平台默认智能助手 Agent（code = reception_assistant）
--      - 该编码被 PlatformDefaultAssistantPolicy 锁死：不可停用、不可删除、编码不可改；
--      - 也正因为它锁编码，后台「新建 Agent」不允许别的行占用该编码 → 只能由种子创建。
--   C. 默认对话入口方案（ma_def.template = ENTRY_ROUTER，is_reception_default = 1）
--      - ReceptionSchemeResolver 只认这一条默认位；没有它工作台直接「未就绪」。
--   D. 入口方案的第一条候选（接待助手）
--      - 方案没有启用候选时，解析器报「还没有可用的路由目标」，会话建不出来。
--   E. 两个工作台权限码
--      - runtime:chat:use   → /api/runtime/workbench/** 的接口权限；
--      - ma:def:reception   → 工作台未就绪时「去配置」按钮的显隐。
--   F. 接待助手的 CHAT 模型绑定（**有可用实例才补**）
--      - TrialBindingLoader#findChatModel 只读 agent_model_bind 的 CHAT 槽，
--        不会因为平台有 CHAT 实例就借给 Agent；缺绑定报「Agent 没有可用的对话模型」；
--      - 模型 Key 只能界面加密保存，SQL 造不出实例 → 没有实例时本段整段跳过。
--   G. 内置工具 browserUse 的说明文案（与当前会话能力对齐）
--      - tool_item 行仍由 sql/data-init.sql 种子；能力增强后的 description 在本文件幂等写回，
--        避免把运行期工具说明塞进 sys / 平台基础种子。
--
-- 【本文件不装什么（都在测试脚本或界面）】
--   * 三个示例专家（知识问答 / 文稿起草 / 需求拆解）→ sql/data-init-test-tmp.sql
--   * 三个示例 Skill 及其版本、专家绑定、专家入池 → sql/data-init-test-tmp.sql
--   * 工作流「制度要点与报告」定义与草稿、工作流入池 → sql/data-init-test-tmp.sql
--   * 模型供应商 / 凭证 / 实例、知识库 / 文档：一律界面配置，任何 SQL 都不写
--   * workflow_release：发布必须走界面按钮，本文件（及测试脚本）都不伪造
--   * 运行期表（obs_span / obs_trace / agent_run_* / workflow_run /
--     memory_session_extract 等）：不写、不 DROP、不 TRUNCATE、不清任何既有数据
--
-- 【前置】已执行 sql/agenthub.sql 与 sql/data-init.sql。
--
-- 【可重复执行】
--   全部按业务键判存在（template_key / code / permission / route_key / agent_id）：
--   重复执行不产生重复行，不覆盖管理员后来改过的人设、名称与默认位。
--   唯一例外：若管理员故意把接待助手的 CHAT 绑定停用（status=0），
--   重跑会补一条新的启用绑定——这是为了「别再报没有对话模型」，见 F 节注释。
-- =============================================================================

-- 字符集 / 排序规则与 sql/agenthub.sql、Compose collation-server 对齐，
-- 避免临时表继承库默认后与 prompt_* 等 general_ci 列 JOIN 报 ERROR 1267。
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;
USE agenthub;

-- 一期租户由配置提供（默认 1）；部署到别处时改这一行
SET @tenant := 1;

-- 操作人：只写 created_by / updated_by 审计列；优先取管理员，取不到写 1（不影响可见性）
SET @operator := COALESCE((
  SELECT `id` FROM `sys_user`
  WHERE `deleted` = 0 AND `status` = 1
  ORDER BY `is_admin` DESC, `id` ASC
  LIMIT 1
), 1);

-- =============================================================================
-- A. 提示词 L2 段落
-- =============================================================================
-- sql/data-init.sql 只写了 5 条 prompt_template 头，不含 prompt_template_segment；
-- 没有段落时组装会抛 PROMPT_UNAVAILABLE（见 PromptAssembler#assemble /
-- #assembleLockedByPurpose），所以这里是硬前置而不是样例。
--
-- 段落口径：
--   * Core 契约段：seg_key = core_contract，mutable = 0（锁定），required = 1
--   * 风格/角色段：CHAT→role_tone / ROUTER→route_style / REWRITE→rewrite_style /
--     SUMMARY→summary_style，mutable = 1（开放 soft），required = 0
--   * MEMORY 例外：core_contract（抽取契约）+ output_format（输出格式）两段都锁定，
--     记忆抽取运行时只拼这两段；Agent 只能追加要求，不能用旧 key/value 格式覆盖
--   * 同 purpose + locale 最终只留 1 条 is_active = 1；存量旧键（如 CHAT_DEFAULT）
--     只熄灭 is_active，不删行、不改正文，版本历史仍可查
-- -----------------------------------------------------------------------------

-- A.0 本次种子覆盖的用途清单（改用途只改这一处）
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_purpose;
CREATE TEMPORARY TABLE tmp_ai_prompt_purpose (
  purpose VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (purpose)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO tmp_ai_prompt_purpose (purpose) VALUES
  ('CHAT'), ('ROUTER'), ('REWRITE'), ('SUMMARY'), ('MEMORY');

-- A.1 用途同名键模板：先以 is_active = 0 落库，第 A.4 步统一点亮，
--     避免插入过程中出现「同用途双生效」的中间态。
--     判存在不带 deleted：唯一键 uk_key_locale_ver 不含 deleted，软删行再插同版本会撞键，
--     所以软删行交给第 A.2 步复活。
INSERT INTO prompt_template (
  template_key, name, purpose, locale, protocol, version, status, is_active, remark,
  deleted, create_by, update_by, create_time, update_time
)
SELECT
  'CHAT', '对话主模板', 'CHAT', 'zh-CN', NULL, 1, 1, 0,
  '种子：CHAT 用途样例（sql/data-init-agent.sql）', 0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_template WHERE template_key = 'CHAT' AND locale = 'zh-CN'
)
UNION ALL
SELECT
  'ROUTER', '路由判定模板', 'ROUTER', 'zh-CN', NULL, 1, 1, 0,
  '种子：ROUTER 用途样例（sql/data-init-agent.sql）', 0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_template WHERE template_key = 'ROUTER' AND locale = 'zh-CN'
)
UNION ALL
SELECT
  'REWRITE', '查询改写模板', 'REWRITE', 'zh-CN', NULL, 1, 1, 0,
  '种子：REWRITE 用途样例（sql/data-init-agent.sql）', 0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_template WHERE template_key = 'REWRITE' AND locale = 'zh-CN'
)
UNION ALL
SELECT
  'SUMMARY', '会话摘要模板', 'SUMMARY', 'zh-CN', NULL, 1, 1, 0,
  '种子：SUMMARY 用途样例（sql/data-init-agent.sql）', 0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_template WHERE template_key = 'SUMMARY' AND locale = 'zh-CN'
)
UNION ALL
SELECT
  'MEMORY', '记忆抽取模板', 'MEMORY', 'zh-CN', NULL, 1, 1, 0,
  '种子：MEMORY 用途样例（sql/data-init-agent.sql）', 0, NULL, NULL, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM prompt_template WHERE template_key = 'MEMORY' AND locale = 'zh-CN'
);

-- A.2 复活：同名键曾被软删的行拉回可用；不碰 name / remark / 版本号。
--     没有软删行时命中 0 行，无副作用。
UPDATE prompt_template t
JOIN tmp_ai_prompt_purpose p ON p.purpose = t.template_key
SET t.deleted = 0,
    t.status = 1,
    t.update_time = NOW()
WHERE t.locale = 'zh-CN'
  AND t.deleted = 1;

-- A.3 段落：只给「一条段落都没有」的同名模板补，已有段落不覆盖人工编辑
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_seg_exist;
CREATE TEMPORARY TABLE tmp_ai_prompt_seg_exist (
  template_id BIGINT NOT NULL,
  PRIMARY KEY (template_id)
) ENGINE = InnoDB;

INSERT INTO tmp_ai_prompt_seg_exist (template_id)
SELECT template_id
FROM prompt_template_segment
WHERE deleted = 0
GROUP BY template_id;

INSERT INTO prompt_template_segment (
  template_id, seg_key, display_name, sort_no, mutable, required, content,
  deleted, create_time, update_time
)
SELECT
  t.id, s.seg_key, s.display_name, s.sort_no, s.mutable, s.required, s.content,
  0, NOW(), NOW()
FROM prompt_template t
JOIN tmp_ai_prompt_purpose p ON p.purpose = t.purpose
JOIN (
  -- ===== CHAT（阻断项）：直接回答 + 专业简洁语气 =====
  SELECT
    'CHAT' AS purpose, 'core_contract' AS seg_key, '输出契约' AS display_name,
    10 AS sort_no, 0 AS mutable, 1 AS required,
    '【输出契约】\n1. 先直接回答问题，再补充必要的解释；信息不足时明确说明缺什么，不要猜。\n2. 使用与用户本轮提问相同的语言，不中英混写叙述。\n3. 只使用平台已绑定并启用的工具；不得声称能调用未提供的接口。\n4. 不得编造未从用户或工具处得到的业务数据、订单与系统状态。\n5. 涉及金额、时间、身份等关键结论时，说明依据来自哪一轮输入或哪个工具结果。' AS content
  UNION ALL
  SELECT
    'CHAT', 'role_tone', '角色与语气', 20, 1, 0,
    '【角色与语气】\n你是企业的智能助理，语气专业、简洁、可执行。\n默认用短段落与要点列表，避免空话套话。\n专业术语首次出现时补一句白话解释；不确定的地方直说不确定，而不是含糊带过。\n用户明显着急时先给结论与下一步，再补细节。'

  -- ===== ROUTER（阻断项）：只判意图、只出「键 + 子问题 + 一句依据」 =====
  UNION ALL
  SELECT
    'ROUTER', 'core_contract', '路由契约', 10, 0, 1,
    '【路由契约】\n你只做意图判定与派发，不回答业务内容。\n只能从本轮给出的【可用专家】里选择，不得新造、合并或指向名单外的目标；确实无人能接时派给指定的兜底专家。\n先读各候选的职责简介，再对照用户本轮诉求判断由谁承接，不要只看字面关键词。\n输出只允许一段 JSON 数组（单意图时也只有一项），不要 Markdown 代码块、不要前后解释：\n[{"route_key":"<候选键>","sub_query":"<切给该专家的子问题>","reason":"<一句话依据>"}]\nreason 必填：一句话说明为什么选这位，必须引用该候选的职责或用户请求的关键特征；不得出现 route_key、模板名、父模型、子模型、作为 AI 模型、根据系统设定等内部说法。'
  UNION ALL
  SELECT
    'ROUTER', 'route_style', '判定口径', 20, 1, 0,
    '【判定口径】\n按用户本轮的真实诉求判，不按字面关键词。\n多意图并存时按重要性排序，同一 route_key 只出现一次。\n纯寒暄、致谢、询问能力或诉求不清晰时，派给兜底专家。\nreason 只写一句结论性依据，不展开推理过程，也不要复述候选清单。'

  -- ===== REWRITE（平台目录项，非阻断）：只出改写后的查询 =====
  UNION ALL
  SELECT
    'REWRITE', 'core_contract', '改写契约', 10, 0, 1,
    '【改写契约】\n只输出改写后的查询语句本身，不要解释、不要加引号或 Markdown 标记。\n保留原问中的实体、数字、时间与限定条件，不得新增或删除事实。\n补全指代（如「它」「上面那个」）时，只能使用本轮对话已出现的上下文。\n信息不足时原样保留该部分，不要自行猜测替换。'
  UNION ALL
  SELECT
    'REWRITE', 'rewrite_style', '改写风格', 20, 1, 0,
    '【改写风格】\n把口语化、冗长的表述压缩成检索友好的短句，一般不超过 40 字。\n保留原问语言，中文场景不要输出英文关键词堆砌。\n去掉「请问」「帮我看看」这类无检索价值的礼貌前缀。'

  -- ===== SUMMARY（平台目录项，非阻断）：按时间顺序的要点式摘要 =====
  UNION ALL
  SELECT
    'SUMMARY', 'core_contract', '摘要契约', 10, 0, 1,
    '【摘要契约】\n只依据输入的对话内容生成摘要，不得引入外部信息或推测未出现的内容。\n保留关键实体、数字、结论与待办；丢弃寒暄与重复表述。\n按时间顺序叙述，使用与原文一致的语言。\n未在原文出现的数字与承诺一律不得写入摘要。'
  UNION ALL
  SELECT
    'SUMMARY', 'summary_style', '摘要风格', 20, 1, 0,
    '【摘要风格】\n输出 3 到 6 条要点，每条独占一行并以「- 」开头。\n单条不超过 40 字，优先用动宾结构，例如「确认退款金额 199 元」。\n本轮没有实质信息时只输出一行「- 本轮无实质内容」。'

  -- ===== MEMORY（阻断项）：平台锁定「抽取契约 + 输出格式」，运行时只拼这两段 =====
  UNION ALL
  SELECT
    'MEMORY', 'core_contract', '抽取契约', 10, 0, 1,
    '【抽取契约】\n你是长期记忆抽取器。只从用户话语里抽取可长期成立的事实，不回答用户、不分析题意、不当场处理用户请求。\nfact_key 为小写 snake_case（仅 [a-z0-9_]，≤64）；同一事实必须同一 key，禁止近义乱造。\n用户更正已有事实时必须复用原事实的 fact_key（例如「感冒好了」仍用 health_status），并在 content 写最新结论；系统只会更新抽到的条目，未抽到的条目不会被删除或改写，因此状态结束时必须把由该状态带出的相关条目一并重新输出。\n例：先说「这周感冒、忌生冷」，后说「感冒好了」→ 必须同时输出 health_status（内容改为已康复）与 dietary_restriction（内容改为已无生冷限制）。这类「状态已结束」的覆盖不要填 ttl_days=0（避免永久占召回），用短寿命 1～7 天覆写旧行后再过期清掉。\nttl_days 按「这条信息为什么会成立」判断真实寿命，不要按 fact_key 类别套固定值：\n- 原因本身是长期或终身的填 0（永久），例如手机号、居住地、过敏体质、姓名、语言偏好；\n- 原因是一次性临时状态时，填该状态的寿命，例如「这周感冒、忌生冷」填 7、「这三个月在学钢琴」填 90；\n- 同一个临时原因带出的多条必须用相同 ttl_days，禁止把同一件事拆成一条永久、一条临时；\n- dietary_restriction 必须看原因：过敏 / 宗教 / 素食等长期禁忌填 0；因感冒、肠胃炎、孕期、短期医嘱等临时原因忌口，则跟随该原因的天数；\n- health_status 这类短期身体状态不要填 0，除非用户明确说这是长期或慢性病；\n- 拿不准就填 0。禁止为凑格式编造过短的 TTL。\n常用键示例：residence_city（居住/常驻城市）、dietary_restriction（饮食禁忌）、language_preference（语言偏好）、timezone、occupation。\n应抽取：居住/常驻城市、用户自称的工作或通勤城市、回答格式/语言偏好、明确禁忌、有明确时效的短期状态。\n不要抽取：一次性行程、当次天气查询、工具原文、密钥、会话临时结论。'
  UNION ALL
  SELECT
    'MEMORY', 'output_format', '输出格式', 20, 0, 1,
    '【输出格式】\n只输出一个 JSON 数组。禁止输出分析、解释、Markdown 或其它文字。\n每项必须含：fact_key、title、content、importance（0-100 整数）、ttl_days（整数天数，0=永久）。\n没有可长期成立的条目时，只输出：[]'
) s ON s.purpose = t.purpose
LEFT JOIN tmp_ai_prompt_seg_exist e ON e.template_id = t.id
WHERE t.deleted = 0
  AND t.locale = 'zh-CN'
  -- 只对「用途同名键」补段落；存量旧键（CHAT_DEFAULT 等）不动
  AND t.template_key = t.purpose
  AND e.template_id IS NULL;

-- A.4 生效收敛：同 purpose + locale 只留 1 条 is_active = 1
-- A.4.1 先熄灭本脚本已备好同名键的用途下、其它 key 的生效行
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_seeded;
CREATE TEMPORARY TABLE tmp_ai_prompt_seeded (
  purpose VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
  PRIMARY KEY (purpose)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT INTO tmp_ai_prompt_seeded (purpose)
SELECT DISTINCT t.purpose
FROM prompt_template t
JOIN tmp_ai_prompt_purpose p ON p.purpose = t.purpose
WHERE t.deleted = 0
  AND t.locale = 'zh-CN'
  AND t.status = 1
  AND t.template_key = t.purpose;

UPDATE prompt_template t
JOIN tmp_ai_prompt_seeded sp ON sp.purpose = t.purpose
SET t.is_active = 0,
    t.update_time = NOW()
WHERE t.deleted = 0
  AND t.locale = 'zh-CN'
  AND t.is_active = 1
  -- 同名键且已发布的目标行留给 A.4.2 点亮，这里不碰
  AND NOT (t.template_key = t.purpose AND t.status = 1);

-- A.4.2 再点亮「用途同名键的最高已发布版本」，每个用途恰好一行
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_pick;
CREATE TEMPORARY TABLE tmp_ai_prompt_pick (
  id BIGINT NOT NULL,
  PRIMARY KEY (id)
) ENGINE = InnoDB;

INSERT INTO tmp_ai_prompt_pick (id)
SELECT t.id
FROM prompt_template t
JOIN (
  SELECT purpose, MAX(version) AS max_version
  FROM prompt_template
  WHERE deleted = 0
    AND locale = 'zh-CN'
    AND status = 1
    AND template_key = purpose
  GROUP BY purpose
) pick ON pick.purpose = t.purpose AND pick.max_version = t.version
JOIN tmp_ai_prompt_purpose p ON p.purpose = t.purpose
WHERE t.deleted = 0
  AND t.locale = 'zh-CN'
  AND t.status = 1
  AND t.template_key = t.purpose;

UPDATE prompt_template t
JOIN tmp_ai_prompt_pick a ON a.id = t.id
SET t.is_active = 1,
    t.update_time = NOW();

-- A.5 清理临时表（会话级，退出连接也会消失；显式删一次便于同会话重跑）
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_pick;
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_seeded;
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_seg_exist;
DROP TEMPORARY TABLE IF EXISTS tmp_ai_prompt_purpose;

-- =============================================================================
-- 公共变量：生效 CHAT 模板 + 可复用的 CHAT 实例
-- =============================================================================
SET @chat_prompt_id := (
  SELECT `id` FROM `prompt_template`
  WHERE `purpose` = 'CHAT' AND `locale` = 'zh-CN' AND `status` = 1 AND `is_active` = 1 AND `deleted` = 0
  ORDER BY `version` DESC, `id` DESC LIMIT 1
);
SET @chat_prompt_version := (
  SELECT `version` FROM `prompt_template` WHERE `id` = @chat_prompt_id
);

-- 可用 CHAT 实例：实例 / 供应商 / 凭证三者都启用，且凭证密文非空。
-- 只挑现成实例，**不创建**任何模型行，也不会把 Key 写进 SQL。
-- 没有可用实例时 @chat_model_id 为 NULL：F 节整段跳过，自检会显示 NULL。
-- 兼容历史 LLM 码（与运行期 ModelCapability 的旧码映射同口径）。
SET @chat_model_id := (
  SELECT mi.`id`
  FROM `model_instance` mi
  JOIN `model_provider` mp ON mp.`id` = mi.`provider_id`
  JOIN `model_credential` mc ON mc.`id` = mi.`credential_id`
  WHERE mi.`deleted` = 0 AND mi.`status` = 1
    AND mi.`capability` IN ('CHAT', 'LLM')
    AND mp.`deleted` = 0 AND mp.`status` = 1
    AND mc.`deleted` = 0 AND mc.`status` = 1
    AND mc.`api_key_cipher` IS NOT NULL AND mc.`api_key_cipher` <> ''
  ORDER BY mi.`id` ASC
  LIMIT 1
);

-- =============================================================================
-- B. 平台默认智能助手 Agent（code 被后端锁死，只能由种子创建）
-- =============================================================================
-- 创建即钉平台生效的 CHAT 模板：人设走 prompt_extra 追加区，不写 system_prompt；
-- 简介必须写清「何时该选 / 何时不该选」，它是判路由唯一的意图说明（单源）。
-- 只在不存在时创建；已存在时不覆盖管理员改过的人设与简介。
-- -----------------------------------------------------------------------------
INSERT INTO `agent_def`
  (`name`, `code`, `description`, `run_mode`, `prompt_template_id`, `template_version`, `prompt_extra`,
   `status`, `allow_orchestrated`, `remark`, `deleted`, `created_by`, `create_time`, `updated_by`, `update_time`)
SELECT '接待助手', 'reception_assistant',
       '平台默认接待助手：先接住用户，处理寒暄、能力介绍与说不清时的一句追问；能答的浅问直接答。不负责知识库检索作答、正式文稿起草，也不做复杂需求拆解——这些应交给对应专家。',
       'REACT',
       @chat_prompt_id, @chat_prompt_version,
       '你是平台的接待助手，负责在正式对话入口先接住用户。\n'
       '- 用户打招呼、寒暄时，简短回应，并用一两句话说明你可以帮什么；只介绍平台已接入的能力，不编造。\n'
       '- 用户需求清楚时，先用一句话确认理解，再给出明确、可执行的回答，不要只反问。\n'
       '- 用户表述含糊或缺少关键条件时，先追问一句最关键的澄清问题；用户补充后再处理，不要一次抛出多个问题。\n'
       '- 不确定能否处理时，如实说明限制并给出下一步建议；不要假装已经执行。\n'
       '- 回答使用简体中文，简洁直接；不要输出路由、模型、工具等内部细节。',
       1, 1, '工作台默认接待种子；人设走追加区，可在「编排 → 多智能体 → 方案详情」修改', 0, @operator, NOW(), @operator, NOW()
FROM DUAL
WHERE @chat_prompt_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `agent_def` WHERE `code` = 'reception_assistant' AND `deleted` = 0
  );

-- B.1 存量行补简介：只在空 / 占位时写。
--     额外认刀1 时代的接待短简介（没有「何时不该选」边界，路由分不清专家），
--     升级为可区分的说明；人手改过就不相等，不会被覆盖。
UPDATE `agent_def`
SET `description` = '平台默认接待助手：先接住用户，处理寒暄、能力介绍与说不清时的一句追问；能答的浅问直接答。不负责知识库检索作答、正式文稿起草，也不做复杂需求拆解——这些应交给对应专家。',
    `updated_by` = @operator,
    `update_time` = NOW()
WHERE `code` = 'reception_assistant' AND `deleted` = 0
  AND (
    `description` IS NULL
    OR TRIM(`description`) = ''
    OR TRIM(`description`) IN ('暂无简介', '请填写简介', '请填写', 'TODO', '-', '无', '待补充', '简介')
    OR TRIM(`description`) = '平台默认接待助手：先接住用户，能答就答，说不清先追问一句。'
  );

-- B.2 存量行未钉 CHAT 模板：只补钉，不覆盖已有 prompt_extra 与人设
UPDATE `agent_def`
SET `prompt_template_id` = @chat_prompt_id,
    `template_version` = @chat_prompt_version,
    `updated_by` = @operator,
    `update_time` = NOW()
WHERE `code` = 'reception_assistant'
  AND `deleted` = 0
  AND `prompt_template_id` IS NULL
  AND @chat_prompt_id IS NOT NULL;

SET @assistant_id := (
  SELECT `id` FROM `agent_def`
  WHERE `code` = 'reception_assistant' AND `deleted` = 0
  ORDER BY `id` ASC LIMIT 1
);

-- =============================================================================
-- C. 默认对话入口方案（ENTRY_ROUTER 虚拟根）
-- =============================================================================
-- 入口是虚拟根（root_agent_id = NULL）：不能绑 ROUTER Agent，
-- 由服务端默认接待直接建会话。模板固定 ENTRY_ROUTER（全库每租户仅一条，
-- 数据库生成列 entry_slot + 唯一索引兜底），执行语义与 ROUTER_PARALLEL 一致。
-- 名称「Router」与注释是入口身份字段：页面只读、后端拒绝修改；
-- 判路由模型留空，运行期按「方案配置 → 平台 ROUTER → 平台 CHAT」回落。
-- 只在不存在时创建：已存在时不改名称、不改配置、不重置默认位。
-- -----------------------------------------------------------------------------
INSERT INTO `ma_def`
  (`tenant_id`, `code`, `name`, `description`, `template`, `config_json`, `root_agent_id`,
   `enabled`, `version`, `is_reception_default`, `created_by`, `create_time`, `updated_by`, `update_time`)
SELECT @tenant, 'reception_default', 'Router',
       '对话入口：负责规划、选择专家或普通方案并决定是否收尾；不可删除、不可改名称/类型、不可加入路由池、不可作为子专家。',
       'ENTRY_ROUTER',
       '{"maTemplate":"ENTRY_ROUTER","maContextPolicy":"ISOLATED","multiIntentEnabled":false,'
       '"answerMode":"CONCAT","maxRouteChildren":1,"requirePlanConfirm":false,'
       '"maChildRunModesAllowed":["REACT","PLAN_AND_EXECUTE"]}',
       NULL, 1, 1, 0, @operator, NOW(), @operator, NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `ma_def` WHERE `tenant_id` = @tenant AND `code` = 'reception_default'
);

-- 解析入口方案：优先当前默认位，没有默认位时回落到种子 code。
-- 注意：ma_def 没有 deleted 列，不要在这里加 deleted 条件。
SET @reception_ma_id := COALESCE((
  SELECT `id` FROM `ma_def`
  WHERE `tenant_id` = @tenant AND `is_reception_default` = 1
  ORDER BY `id` ASC LIMIT 1
), (
  SELECT `id` FROM `ma_def`
  WHERE `tenant_id` = @tenant AND `code` = 'reception_default'
  ORDER BY `id` ASC LIMIT 1
));

-- 只在「本租户还没有默认接待」时补默认位：重跑不会覆盖管理员改过的默认设置，
-- 也不会撞 uk_ma_def_reception（生成列 reception_slot 的唯一索引）。
SET @has_default := (
  SELECT COUNT(*) FROM `ma_def`
  WHERE `tenant_id` = @tenant AND `is_reception_default` = 1
);
UPDATE `ma_def`
SET `is_reception_default` = IF(@has_default = 0, 1, `is_reception_default`),
    `updated_by` = @operator, `update_time` = NOW()
WHERE `id` = @reception_ma_id;

-- =============================================================================
-- D. 入口方案的第一条候选（接待助手）
-- =============================================================================
-- 方案没有启用候选时，ReceptionSchemeResolver 直接判未就绪，会话建不出来。
-- 简介为空时用兜底文案写 intent_desc：intent_desc 是 NOT NULL 列，
-- 缺了这条候选工作台就没有可用目标，宁可写兜底也不留空。
-- 刀4 起判路由只读 agent_def.description；intent_desc 仅作兼容快照，统一取简介。
-- -----------------------------------------------------------------------------
INSERT INTO `ma_candidate`
  (`ma_id`, `target_type`, `agent_id`, `ma_def_id`, `workflow_def_id`,
   `route_key`, `intent_desc`, `sort_no`, `enabled`)
SELECT @reception_ma_id, 'AGENT', a.`id`, NULL, NULL,
       'reception_assistant',
       IF(a.`description` IS NULL OR TRIM(a.`description`) = '',
          '通用接待：寒暄、能力介绍、需求不清时先追问一句',
          LEFT(TRIM(a.`description`), 512)),
       1, 1
FROM `agent_def` a
WHERE @reception_ma_id IS NOT NULL
  AND a.`id` = @assistant_id
  AND a.`deleted` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `ma_candidate`
    WHERE `ma_id` = @reception_ma_id
      AND (`agent_id` = a.`id` OR `route_key` = 'reception_assistant')
  );

-- 意图说明单源对齐：本脚本负责的这条候选，快照过期时对齐一次；不动管理员自建候选。
UPDATE `ma_candidate` c
JOIN `agent_def` a ON a.`id` = c.`agent_id`
SET c.`intent_desc` = LEFT(TRIM(a.`description`), 512)
WHERE c.`ma_id` = @reception_ma_id
  AND c.`target_type` = 'AGENT'
  AND a.`code` = 'reception_assistant'
  AND a.`deleted` = 0
  AND a.`description` IS NOT NULL AND TRIM(a.`description`) <> ''
  AND (c.`intent_desc` IS NULL OR c.`intent_desc` <> LEFT(TRIM(a.`description`), 512));

-- =============================================================================
-- E. 两个工作台权限码
-- =============================================================================
--   ma:def:reception = 维护对话入口（隐藏按钮；默认接待页已整页下线，编辑走方案详情）
--   runtime:chat:use = 使用对话（/api/runtime/workbench/** 的接口权限，菜单行隐藏）
-- 这两码是工作台能不能被打开 / 能不能对话的开关，属运行必需；
-- 数据本身在 sys_menu，但生命周期跟工作台入口走，所以放本文件而不是 data-init.sql。
-- -----------------------------------------------------------------------------
SET @orch_parent := (
  SELECT `id` FROM `sys_menu`
  WHERE `deleted` = 0 AND (`name` = '编排' OR `path` = '/orchestration')
  ORDER BY (`name` = '编排') DESC, `id` ASC
  LIMIT 1
);
SET @ma_menu := (
  SELECT `id` FROM `sys_menu`
  WHERE `deleted` = 0 AND `path` = '/orchestration/multi-agent'
  ORDER BY `id` ASC LIMIT 1
);

-- 默认接待页面已下线：历史行只隐藏、不物理删除，避免破坏既有角色授权记录
UPDATE `sys_menu`
SET `visible` = 0, `updated_by` = @operator, `update_time` = NOW()
WHERE `deleted` = 0
  AND `path` = '/orchestration/reception'
  AND `visible` <> 0;

INSERT INTO `sys_menu`
  (`parent_id`, `type`, `name`, `path`, `component`, `permission`, `icon`, `sort`,
   `visible`, `status`, `open_type`, `deleted`, `created_by`, `create_time`, `updated_by`, `update_time`)
SELECT COALESCE(@ma_menu, @orch_parent,
                (SELECT `id` FROM `sys_menu` WHERE `id` = 56 AND `deleted` = 0), 0),
       3, '维护对话入口', NULL, NULL,
       'ma:def:reception', NULL, 3, 0, 1, 0, 0, @operator, NOW(), @operator, NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `deleted` = 0 AND `permission` = 'ma:def:reception'
);

INSERT INTO `sys_menu`
  (`parent_id`, `type`, `name`, `path`, `component`, `permission`, `icon`, `sort`,
   `visible`, `status`, `open_type`, `deleted`, `created_by`, `create_time`, `updated_by`, `update_time`)
SELECT COALESCE(@orch_parent,
                (SELECT `id` FROM `sys_menu` WHERE `id` = 56 AND `deleted` = 0), 0),
       3, '使用对话', NULL, NULL,
       'runtime:chat:use', NULL, 32, 0, 1, 0, 0, @operator, NOW(), @operator, NOW()
FROM DUAL
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_menu` WHERE `deleted` = 0 AND `permission` = 'runtime:chat:use'
);

-- 管理员默认拥有这两个权限码（隐藏按钮行），按 (role_id, menu_id) 幂等。
-- 其它角色仍需在「系统 → 角色」里勾选；本脚本不替管理员授权。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, m.`id`
FROM `sys_menu` m
WHERE m.`deleted` = 0
  AND m.`permission` IN ('ma:def:reception', 'runtime:chat:use')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` rm WHERE rm.`role_id` = 1 AND rm.`menu_id` = m.`id`
  );

-- =============================================================================
-- F. 接待助手的 CHAT 模型绑定（有可用实例才补）
-- =============================================================================
-- 为什么必须写：运行期 TrialBindingLoader#findChatModel 只读 agent_model_bind 的 CHAT 槽
-- （purpose=CHAT，或 purpose 为空且实例是对话类），**不会**因为平台有 CHAT 实例就自动借给 Agent；
-- 缺绑定直接抛「Agent 没有可用的对话模型」，工作台第一句就发不出去。
-- 为什么不能写死：Key 只能界面加密保存，SQL 里造不出实例；
-- 所以只在「已有可用 CHAT 实例」时补，没有就整段跳过（自检会提示下一步）。
-- 幂等：只给「当前没有任何启用中的 CHAT 绑定」的接待助手补；已有绑定一律跳过，
-- 多条可用实例时取 id 最小的一条，之后可在「Agent → 模型配置」里调整。
-- -----------------------------------------------------------------------------
INSERT INTO `agent_model_bind`
  (`agent_id`, `model_instance_id`, `purpose`, `priority`, `status`, `deleted`,
   `created_by`, `create_time`, `updated_by`, `update_time`)
SELECT a.`id`, @chat_model_id, 'CHAT', 100, 1, 0, @operator, NOW(), @operator, NOW()
FROM `agent_def` a
WHERE @chat_model_id IS NOT NULL
  AND a.`deleted` = 0
  AND a.`status` = 1
  AND a.`code` = 'reception_assistant'
  AND NOT EXISTS (
    SELECT 1
    FROM `agent_model_bind` b
    JOIN `model_instance` mi ON mi.`id` = b.`model_instance_id`
    JOIN `model_provider` mp ON mp.`id` = mi.`provider_id`
    JOIN `model_credential` mc ON mc.`id` = mi.`credential_id`
    WHERE b.`agent_id` = a.`id`
      AND b.`deleted` = 0
      AND b.`status` = 1
      AND mi.`deleted` = 0
      AND mi.`status` = 1
      AND mp.`deleted` = 0 AND mp.`status` = 1
      AND mc.`deleted` = 0 AND mc.`status` = 1
      AND mc.`api_key_cipher` IS NOT NULL AND mc.`api_key_cipher` <> ''
      -- purpose 为空表示「跟实例」：只有实例本身是对话类才算占了 CHAT 槽
      AND (
        b.`purpose` = 'CHAT'
        OR ((b.`purpose` IS NULL OR b.`purpose` = '') AND mi.`capability` IN ('CHAT', 'LLM'))
      )
  );

-- =============================================================================
-- G. 内置工具 browserUse 说明（与 BrowserTool.DESCRIPTION 同一句）
-- =============================================================================
-- sql/data-init.sql 只负责插入 tool_item 行；能力增强后的说明属于运行必需的非 sys 配置，
-- 在此幂等写回。可重复执行；不改 name / title / input_schema_json / status。
-- -----------------------------------------------------------------------------
UPDATE `tool_item` i
INNER JOIN `tool_source` s ON s.`id` = i.`source_id` AND s.`type` = 'BUILTIN' AND s.`deleted` = 0
SET i.`description` = '在同一浏览器会话里打开公网页面，并可继续填表、点击、截图、读取标题和可见文本。不能自动识别验证码，也不能代为登录；遇到验证码会交回截图或提示。仅允许公网 http/https，禁止内网。',
    i.`update_time` = NOW(),
    i.`updated_by` = @operator
WHERE i.`name` = 'browserUse'
  AND i.`deleted` = 0;

-- =============================================================================
-- H. 自检（只读；不进任何业务表）
-- =============================================================================
-- H.1 提示词：每个生效用途应有 2 段；MEMORY 两段都应锁定（locked_segments = 2）
SELECT t.`purpose`, t.`template_key`, t.`version`, t.`is_active`,
       (SELECT COUNT(*) FROM `prompt_template_segment` s
         WHERE s.`template_id` = t.`id` AND s.`deleted` = 0) AS segments,
       (SELECT COUNT(*) FROM `prompt_template_segment` s
         WHERE s.`template_id` = t.`id` AND s.`deleted` = 0 AND s.`mutable` = 0) AS locked_segments
FROM `prompt_template` t
WHERE t.`deleted` = 0 AND t.`locale` = 'zh-CN' AND t.`is_active` = 1
ORDER BY FIELD(t.`purpose`, 'CHAT', 'ROUTER', 'REWRITE', 'SUMMARY', 'MEMORY'), t.`id`;

-- H.2 默认智能助手：chat_binds 应为 1（为 0 说明还没配 CHAT 实例，去界面配好后重跑本文件）
SELECT a.`code`, a.`name`, a.`status`, a.`run_mode`,
       a.`prompt_template_id`, a.`template_version`, a.`allow_orchestrated`,
       (SELECT COUNT(*)
          FROM `agent_model_bind` b
          JOIN `model_instance` mi ON mi.`id` = b.`model_instance_id`
          JOIN `model_provider` mp ON mp.`id` = mi.`provider_id`
          JOIN `model_credential` mc ON mc.`id` = mi.`credential_id`
         WHERE b.`agent_id` = a.`id` AND b.`deleted` = 0 AND b.`status` = 1
           AND mi.`deleted` = 0 AND mi.`status` = 1
           AND mp.`deleted` = 0 AND mp.`status` = 1
           AND mc.`deleted` = 0 AND mc.`status` = 1
           AND mc.`api_key_cipher` IS NOT NULL AND mc.`api_key_cipher` <> ''
           AND (b.`purpose` = 'CHAT'
                OR ((b.`purpose` IS NULL OR b.`purpose` = '') AND mi.`capability` IN ('CHAT', 'LLM')))
       ) AS chat_binds
FROM `agent_def` a
WHERE a.`deleted` = 0 AND a.`code` = 'reception_assistant';

-- H.3 入口方案与候选：应有一条 ENTRY_ROUTER，且至少一条启用候选
SELECT `id`, `tenant_id`, `code`, `name`, `template`, `enabled`, `is_reception_default`
FROM `ma_def`
WHERE `tenant_id` = @tenant AND `template` = 'ENTRY_ROUTER'
ORDER BY `id`;

SELECT c.`sort_no`, c.`target_type`, c.`route_key`, c.`enabled`,
       a.`name` AS agent_name,
       w.`name` AS workflow_name
FROM `ma_candidate` c
LEFT JOIN `agent_def` a ON a.`id` = c.`agent_id`
LEFT JOIN `workflow_def` w ON w.`id` = c.`workflow_def_id`
WHERE c.`ma_id` = @reception_ma_id
ORDER BY c.`sort_no` ASC, c.`id` ASC;

-- H.4 两个权限码
SELECT `id`, `parent_id`, `type`, `name`, `permission`, `visible`
FROM `sys_menu`
WHERE `deleted` = 0 AND `permission` IN ('ma:def:reception', 'runtime:chat:use')
ORDER BY `id`;

-- H.5 缺口诊断：一张表看清「运行还差哪一步」
SELECT
  @chat_prompt_id AS chat_prompt_id,
  @chat_model_id AS bound_chat_instance_id,
  (SELECT COUNT(*) FROM `model_instance`
    WHERE `deleted` = 0 AND `status` = 1 AND `capability` IN ('CHAT', 'LLM')) AS enabled_chat_instances,
  (SELECT COUNT(*) FROM `agent_def` a
    WHERE a.`deleted` = 0 AND a.`code` = 'reception_assistant'
      AND EXISTS (SELECT 1 FROM `agent_model_bind` b
                   WHERE b.`agent_id` = a.`id` AND b.`deleted` = 0 AND b.`status` = 1)) AS assistant_with_model,
  (SELECT COUNT(*) FROM `ma_candidate` c
    WHERE c.`ma_id` = @reception_ma_id AND c.`enabled` = 1) AS enabled_candidates,
  CASE
    WHEN @chat_prompt_id IS NULL
      THEN '阻断：没有生效的 CHAT 提示词模板，先执行 sql/data-init.sql'
    WHEN @chat_model_id IS NULL
      THEN '下一步：去「接入 → 模型管理」建供应商 / 凭证 / 至少 1 个启用 CHAT 实例，然后重跑本文件补绑定'
    WHEN (SELECT COUNT(*) FROM `ma_candidate` c WHERE c.`ma_id` = @reception_ma_id AND c.`enabled` = 1) = 0
      THEN '阻断：默认接待方案没有启用候选，检查本文件 D 节是否写入成功'
    ELSE 'OK：工作台运行必需项已就绪；要示例专家 / 技能 / 工作流，另跑 sql/data-init-test-tmp.sql（可选）'
  END AS next_step;

-- =============================================================================
-- 执行完的人工动作（SQL 不代做）
--   1) 若 H.5 提示没有 CHAT 实例：界面配模型 → 重跑本文件（幂等补绑定）
--   2) 工作台新建会话，先与接待助手说一句话，确认不报「Agent 没有可用的对话模型」
--   3) 需要示例专家 / Skill / 工作流做演示或测试：另跑 sql/data-init-test-tmp.sql（可选，非必需）
-- =============================================================================
