-- =============================================================================
-- 动态神话互动叙事系统 · 数据库初始化统一入口 (init-all.sql)
-- =============================================================================
-- 用法：在 MySQL Workbench 的「shd」连接下打开本文件，直接整段执行即可。
-- 库名：bs（用户已预先建好）
-- 顺序：选库 → 建表（依赖拓扑：god_profile → game_scene → player_profile →
--                    god_relationship → interaction_log）
--       → 约束/索引  → 种子 INSERT（显式列清单，绝不 SELECT *）
-- 设计依据：严格对应《预设 3.0》§五 的 4 张核心表 DDL；最小追加 1 张 player_profile
-- =============================================================================

-- ---------- 0. 上下文 ----------
USE `bs`;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;   -- 建表时临时关闭，避免执行顺序问题；最后重新开启

-- ---------- 1. 神明基础与属性表  §五.1 一字不差 ----------
-- 说明：额外追加 `inventory JSON` 列（MVP 最小补足）——
--       设计文档§六 Walkthrough 要求「雨神持有止水符、玩家持有骨刃+水囊」，
--       但 §五 未给出物品表，故以 JSON 列暂存于 god_profile。
DROP TABLE IF EXISTS `interaction_log`;
DROP TABLE IF EXISTS `god_relationship`;
DROP TABLE IF EXISTS `player_profile`;
DROP TABLE IF EXISTS `game_scene`;
DROP TABLE IF EXISTS `god_profile`;

CREATE TABLE `god_profile` (
  `god_id`       VARCHAR(32) PRIMARY KEY                     COMMENT '神明代号（同时作为玩家实体的god_id）',
  `god_name`     VARCHAR(50) NOT NULL                        COMMENT '神明尊称',
  `god_status`   VARCHAR(50) NOT NULL                        COMMENT '神位(如RAIN, MUSEUM)',
  `god_emotion`  VARCHAR(32) NOT NULL                        COMMENT '主体情绪',
  `base_zhi`     INT          DEFAULT 0                      COMMENT '知-情报量',
  `base_hui`     INT          DEFAULT 0                      COMMENT '慧-推理解题',
  `base_dou`     INT          DEFAULT 0                      COMMENT '斗-战斗力基准',
  `base_wen`     INT          DEFAULT 0                      COMMENT '闻-局势感知',
  `base_ben`     INT CHECK (`base_ben` BETWEEN 0 AND 100)    COMMENT '本-神职感悟度(0~100%)',
  `base_gui`     INT          DEFAULT 0                      COMMENT '归-忠诚意志',
  `inventory`    JSON         DEFAULT (JSON_ARRAY())         COMMENT 'MVP 最小补足：该神明/玩家的背包物品数组 JSON',
  `updated_at`   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='§五.1 神明（含玩家实体）六维基础属性表';

-- ---------- 2. 场景状态与上下文摘要表  §五.3 一字不差 ----------
-- 防坑补足：追加 session_id 列以支持多人独立存档/重新开局（毕设演示防串扰）
CREATE TABLE `game_scene` (
  `scene_id`       VARCHAR(32) PRIMARY KEY                 COMMENT '场景ID',
  `session_id`     VARCHAR(32) NOT NULL                    COMMENT '所属会话/周目ID（防坑规则3：多人独立存档隔离）',
  `location_name`  VARCHAR(50) NOT NULL                    COMMENT '地点名称',
  `coord_x`        INT          DEFAULT 0                  COMMENT '地点坐标 X（玩家回去时定位用，避免 LLM 重复取名）',
  `coord_y`        INT          DEFAULT 0                  COMMENT '地点坐标 Y（玩家回去时定位用，避免 LLM 重复取名）',
  `present_gods`   JSON NOT NULL                           COMMENT '在场NPC列表(JSON字符串数组)',
  `scene_summary`  TEXT         DEFAULT NULL               COMMENT '当前剧情长期滚动摘要(长记忆)',
  `log_count`      INT          DEFAULT 0                  COMMENT 'MVP 小补足：该场景累计交互轮次，用于判断10轮触发scene_summary更新',
  `updated_at`     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_session` (`session_id`),
  KEY `idx_session_coord` (`session_id`, `coord_x`, `coord_y`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='§五.3 场景状态与上下文摘要表（带坐标，支持玩家回去定位）';

-- ---------- 3. 玩家档案表  (spec FR-A5 最小补足) ----------
CREATE TABLE `player_profile` (
  `player_id`       VARCHAR(32)  PRIMARY KEY               COMMENT '玩家账号ID',
  `god_id`          VARCHAR(32)  NOT NULL                  COMMENT '玩家所对应的神明实体ID(→god_profile)',
  `username`        VARCHAR(50)  NOT NULL UNIQUE           COMMENT '登录用户名',
  `password_hash`   VARCHAR(255) NOT NULL                  COMMENT 'BCrypt 密码哈希',
  `inventory`       JSON         DEFAULT (JSON_ARRAY())    COMMENT '玩家独立背包物品(可为空，与god_profile.inventory MVP并存)',
  `current_scene_id` VARCHAR(32) NOT NULL                  COMMENT '玩家当前所在场景(→game_scene)',
  `view_mode`       VARCHAR(16)  DEFAULT 'DREAMER'         COMMENT '视角模式：DREAMER=入梦人(限知) / OMNISCIENT=世界人(全知)',
  `created_at`      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT `fk_player_god`   FOREIGN KEY (`god_id`)          REFERENCES `god_profile`(`god_id`),
  CONSTRAINT `fk_player_scene` FOREIGN KEY (`current_scene_id`) REFERENCES `game_scene`(`scene_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='spec FR-A5 玩家账号与状态表';

-- ---------- 4. 神明动态关系网表  §五.2 一字不差 ----------
-- 防坑补足：追加 session_id，唯一约束改为 (session_id, source_id, target_id)
CREATE TABLE `god_relationship` (
  `relation_id`   BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                     COMMENT '所属会话/周目ID（防坑规则3：多人独立关系网隔离）',
  `source_id`     VARCHAR(32) NOT NULL                     COMMENT '关系发起方 god_id',
  `target_id`     VARCHAR(32) NOT NULL                     COMMENT '关系接受方 god_id',
  `trust_value`   INT CHECK (`trust_value` BETWEEN 0 AND 100) DEFAULT 50 COMMENT '信任度 0~100',
  `is_allied`     TINYINT(1)   DEFAULT 0                   COMMENT '是否结盟',
  `is_backstabbed` TINYINT(1)  DEFAULT 0                   COMMENT 'source 是否背刺过 target',
  `note`          VARCHAR(255) DEFAULT NULL                COMMENT 'MVP 补足：隐秘契约/备注(如EXPLOITATION_RESCUE产生)',
  UNIQUE KEY `uk_session_source_target` (`session_id`, `source_id`, `target_id`),
  KEY `idx_session` (`session_id`),
  CONSTRAINT `fk_rel_source` FOREIGN KEY (`source_id`) REFERENCES `god_profile`(`god_id`),
  CONSTRAINT `fk_rel_target` FOREIGN KEY (`target_id`) REFERENCES `god_profile`(`god_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='§五.2 神明有向动态关系网';

-- ---------- 5. 交互日志表  §五.4 一字不差 ----------
-- 防坑补足：追加 session_id 列 + 索引，支持多周目独立短记忆查询
CREATE TABLE `interaction_log` (
  `round_no` INT DEFAULT 0,
  `log_id`          BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`     VARCHAR(32) NOT NULL                   COMMENT '所属会话/周目ID（防坑规则3：多人独立存档隔离）',
  `player_id`       VARCHAR(32) NOT NULL                   COMMENT '玩家账号ID',
  `scene_id`        VARCHAR(32) NOT NULL                   COMMENT '所属场景',
  `player_input`    TEXT         NOT NULL                   COMMENT '玩家本回合自由文本输入',
  `outcome_type`    VARCHAR(50)  NOT NULL                   COMMENT '本地规则引擎判定的硬性剧情分支类型',
  `intervener_id`   VARCHAR(32)  DEFAULT NULL              COMMENT 'MVP 补足：第三方干预者 god_id',
  `true_intent`     TEXT         NOT NULL                   COMMENT 'NPC真实内心活动JSON(世界人全知视角)',
  `processed_dialog` TEXT        NOT NULL                   COMMENT '古风渲染对白(入梦人限知视角)',
  `token_input`     INT          DEFAULT 0                  COMMENT '本回合 LLM 总输入 tokens(Pass1+Pass2)',
  `token_output`    INT          DEFAULT 0                  COMMENT '本回合 LLM 总输出 tokens',
  `token_total`     INT          DEFAULT 0                  COMMENT '总 tokens = input + output',
  `cost_cny`        DECIMAL(12,6) DEFAULT 0.0               COMMENT '本回合 LLM 成本（元，CNY）',
  `rule_snapshot`   JSON         DEFAULT NULL               COMMENT 'MVP 补足：规则引擎中间分（Cap/Will/Gain/反噬候选等）',
  `created_at`      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session_created` (`session_id`, `created_at` DESC),
  KEY `idx_player_created` (`player_id`, `created_at` DESC),
  KEY `idx_scene_created`  (`scene_id`,  `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='§五.4 回合交互日志表（短记忆Top3查本表）';

-- ---------- 5.1 场景连接图（积木式场景引擎：场景之间可移动关系，模板级，跨 session 复用） ----------
CREATE TABLE `scene_connection` (
  `session_id` VARCHAR(32) DEFAULT NULL,
  `direction` VARCHAR(16) NOT NULL DEFAULT 'OTHER',
  `connection_id`     BIGINT PRIMARY KEY AUTO_INCREMENT,
  `from_scene_id`    VARCHAR(32) NOT NULL                COMMENT '起点场景ID',
  `to_scene_id`      VARCHAR(32) NOT NULL                COMMENT '目标场景ID',
  `exit_name`        VARCHAR(50) NOT NULL                COMMENT '出口名称（古风，如"北去彼岸"）',
  `required_item`    VARCHAR(64) DEFAULT NULL           COMMENT '通过所需物品（可空）',
  `required_objective` VARCHAR(64) DEFAULT NULL          COMMENT '通过所需已完成目标ID（可空）',
  `note`             VARCHAR(255) DEFAULT NULL           COMMENT '路径描述（古风）',
  UNIQUE KEY `uk_from_to_exit` (`from_scene_id`, `to_scene_id`, `exit_name`),
  KEY `idx_from` (`from_scene_id`),
  CONSTRAINT `fk_conn_from` FOREIGN KEY (`from_scene_id`) REFERENCES `game_scene`(`scene_id`),
  CONSTRAINT `fk_conn_to`   FOREIGN KEY (`to_scene_id`)   REFERENCES `game_scene`(`scene_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积木式场景引擎：场景连接图（模板级，跨 session 复用）';

-- ---------- 5.2 玩家目标进度表（积木式场景引擎：主线/支线目标推进，按 session 隔离） ----------
CREATE TABLE `player_objective` (
  `objective_row_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `objective_id`    VARCHAR(64) NOT NULL                 COMMENT '目标ID（如 investigate_flood 查涝灾真凶）',
  `player_id`      VARCHAR(32) NOT NULL                 COMMENT '玩家账号ID',
  `session_id`     VARCHAR(32) NOT NULL                 COMMENT '所属会话/周目ID（防坑规则3）',
  `title`          VARCHAR(100) NOT NULL                COMMENT '目标标题',
  `status`         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/COMPLETED/FAILED',
  `progress`       INT DEFAULT 0                        COMMENT '0-100 进度',
  `current_step`   VARCHAR(255) DEFAULT NULL            COMMENT '当前步骤描述',
  `updated_at`     TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_player_session_obj` (`player_id`, `session_id`, `objective_id`),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家目标进度表（按 session 隔离）';

-- ---------- 5.3 玩家三层记忆表（B2 用，先建表预留：ROUND/SCENE/GLOBAL 三层记忆存储） ----------
CREATE TABLE `player_memory` (
  `memory_id`   BIGINT PRIMARY KEY AUTO_INCREMENT,
  `player_id`  VARCHAR(32) NOT NULL                     COMMENT '玩家账号ID',
  `session_id` VARCHAR(32) NOT NULL                     COMMENT '所属会话/周目ID（防坑规则3）',
  `scope`      VARCHAR(16) NOT NULL                    COMMENT 'ROUND=回合短记忆/SCENE=场景长记忆/GLOBAL=跨场景全局',
  `ref_id`     VARCHAR(64) DEFAULT NULL                 COMMENT 'ROUND→log_id, SCENE→scene_id, GLOBAL→objective_id',
  `content`    TEXT NOT NULL                            COMMENT '原文或摘要',
  `llm_summary` TEXT DEFAULT NULL                      COMMENT 'LLM 提炼的精炼摘要（B2 异步生成）',
  `importance` INT DEFAULT 5                            COMMENT '1-10，用于 Prompt 注入优先级',
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_player_session_scope` (`player_id`, `session_id`, `scope`, `created_at` DESC),
  KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家三层记忆存储（B2 用，先建表预留）';

-- ---------- 5.4 剧情总结元数据表（隐藏的剧情锚点，约束 LLM 不会前后矛盾） ----------
--   核心机制：每次 LLM 生成故事时，要求 LLM 同时输出 visible + hidden_summary 双块。
--   hidden_summary 写入本表，玩家不可见；下次调 Pass1/Pass2 时自动注入 Prompt 约束 LLM。
--   坐标 coord_x/coord_y 用于玩家"回去"时定位已存在场景，避免 LLM 重复取名。
CREATE TABLE `story_summary` (
  `summary_id`   BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`  VARCHAR(32) NOT NULL                COMMENT '所属会话/周目ID（防坑规则3）',
  `scene_id`    VARCHAR(32) DEFAULT NULL           COMMENT '关联场景ID（过场剧情可空）',
  `round_no`    INT DEFAULT 0                       COMMENT '回合序号（本 session 内递增）',
  `coord_x`     INT DEFAULT 0                       COMMENT '地点坐标 X（玩家回去时定位用）',
  `coord_y`     INT DEFAULT 0                       COMMENT '地点坐标 Y（玩家回去时定位用）',
  `location_name` VARCHAR(64) NOT NULL             COMMENT '地点名（如"丰渡水岸"）',
  `story_time`  VARCHAR(32) DEFAULT NULL           COMMENT '剧情内时间（如"戌时三刻"）',
  `plot_summary` TEXT NOT NULL                      COMMENT '本回合剧情概括（一句话说清楚）',
  `present_gods` JSON DEFAULT NULL                 COMMENT '在场角色ID数组',
  `key_events`  JSON DEFAULT NULL                   COMMENT '关键事件数组（如"玩家挑衅雨神"）',
  `state_changes` TEXT DEFAULT NULL                COMMENT '状态变化摘要（如"雨神trust-15, 博物叟trust+10"）',
  `created_at`  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_session_round` (`session_id`, `round_no`),
  KEY `idx_session_round` (`session_id`, `round_no` DESC),
  KEY `idx_session_coord` (`session_id`, `coord_x`, `coord_y`),
  KEY `idx_session_recent` (`session_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='剧情总结元数据表（隐藏锚点 + 坐标定位 + Prompt 注入约束 + 并发 round_no 唯一）';

-- =============================================================================
-- 种子数据：严格对应《预设 3.0》§六 End-to-End Walkthrough
-- =============================================================================
-- §六 关键数值（六维放大公式 实际 = 基础 × (1 + 本/100) 验证）：
--   雨神  ： base_dou=22, base_ben=50 → 22 × 1.5 = 33 ✅
--   玩家  ： base_dou=10, base_ben=20 → 10 × 1.2 = 12 ✅
--   博物神： base_dou=18, base_ben=60 → 18 × 1.6 = 28.8（≈29，规则引擎四舍五入）
--   农神  ： base_dou=3,  base_ben=50 → 3  × 1.5 = 4.5（弱者，Cap<0.4→必排除，见T4 AC-B3）
-- =============================================================================

-- 6.1 god_profile（显式列清单 INSERT）
INSERT INTO `god_profile`
  (`god_id`,`god_name`,`god_status`,`god_emotion`,`base_zhi`,`base_hui`,`base_dou`,`base_wen`,`base_ben`,`base_gui`,`inventory`)
VALUES
  ('player_01',   '你',            'MORTAL', '坚定',   8,  9, 10, 11, 20, 75, JSON_ARRAY('骨刃','水囊')),
  ('rain_god',    '玄暝·雨师',    'RAIN',   '愠怒',  30, 28, 22, 20, 50, 80, JSON_ARRAY('止水符','司雨印')),
  ('museum_god',  '博物叟',       'MUSEUM', '阴鸷',  55, 50, 18, 45, 60, 30, JSON_ARRAY('博古录','窥心镜')),
  ('farm_god',    '后稷·农正',    'FARM',   '平和',  14, 16,  3, 18, 50, 90, JSON_ARRAY('五谷穗'));

-- 6.2 game_scene（丰渡水岸，在场 = 雨神 + 博物神；农神不在场——方便T4单独做"弱者在场"对比测试）
INSERT INTO `game_scene`
  (`scene_id`, `session_id`, `location_name`, `coord_x`, `coord_y`, `present_gods`, `scene_summary`, `log_count`)
VALUES
  ('scene_fengdu', 'sess_demo_001', '丰渡水岸', 0, 0, '["rain_god","museum_god"]',
   '（场景开篇）丰渡水岸，雨师玄暝凭栏望川，止水符悬于其身侧。博物叟隐于古碑之阴，袖手窥伺。', 0);

-- 6.3 player_profile（演示账号：player_demo / 密码 123456，BCrypt 哈希）
-- BCrypt hash of "123456" = $2a$10$oIyGdFYmfCTlCjWNOCyaJODSmNEsL1MZoCd5.31Gj51WuFgISp3Y6
INSERT INTO `player_profile`
  (`player_id`, `god_id`, `username`, `password_hash`, `inventory`, `current_scene_id`, `view_mode`)
VALUES
  ('p_demo', 'player_01', 'player_demo', '$2a$10$oIyGdFYmfCTlCjWNOCyaJODSmNEsL1MZoCd5.31Gj51WuFgISp3Y6',
   JSON_ARRAY(), 'scene_fengdu', 'OMNISCIENT');

-- 6.4 god_relationship（有向图！A 对 B 信任 ≠ B 对 A 信任）
--     关键条目：museum_god → rain_god   is_backstabbed = 1  ←  Walkthrough 三方干预 Gain +0.4 触发点
--     所有种子关系均挂在 sess_demo_001 会话下（防坑规则3：多人独立关系网隔离）
INSERT INTO `god_relationship`
  (`session_id`, `source_id`,    `target_id`,    `trust_value`, `is_allied`, `is_backstabbed`, `note`)
VALUES
  ('sess_demo_001', 'museum_god',   'rain_god',       30, 0, 1, '昔年博物叟曾于河伯之会背刺雨神，抢夺其半卷云图，雨神未知。'),
  ('sess_demo_001', 'rain_god',     'museum_god',     10, 0, 0, '雨师对博物叟素无好感，但未察其旧恶。'),
  ('sess_demo_001', 'player_01',    'rain_god',       40, 0, 0, '玩家对雨神素闻其名，尚未结怨。'),
  ('sess_demo_001', 'rain_god',     'player_01',      55, 0, 0, '雨师初视玩家为凡人过客，不以为意。'),
  ('sess_demo_001', 'museum_god',   'player_01',      10, 0, 0, '博物叟见玩家骨刃在手，暗忖其可为棋子。'),
  ('sess_demo_001', 'player_01',    'museum_god',     60, 0, 0, '玩家视博物叟为寻常长者。');

-- =============================================================================
-- 6.5 积木式场景引擎：多场景图 + 关系网 + 目标 + 记忆预留
--   设计哲学：每场景自包含可复用的剧情单元，通过 scene_connection 拼成大游戏
--   场景链：丰渡水岸 → 北去彼岸 → 彼岸渡口 → 西去忘川 → 忘川桥 → 南下黄泉 → 黄泉渡口
--   玩家主线：investigate_flood 查涝灾真凶（揭穿博物叟才是幕后）
-- =============================================================================

-- 6.5.1 god_profile：扩 4 位新神明（每场景 2-3 NPC；保留原有 rain/museum/farm/player）
INSERT INTO `god_profile`
  (`god_id`,`god_name`,`god_status`,`god_emotion`,`base_zhi`,`base_hui`,`base_dou`,`base_wen`,`base_ben`,`base_gui`,`inventory`)
VALUES
  -- 彼岸渡口：河神（中庸），判官（铁面）
  ('river_god',   '洛川·河伯',   'RIVER',  '中庸', 35, 30, 25, 28, 40, 35, JSON_ARRAY('分水刺','洛书残页')),
  ('judge_god',   '崔珏·判官',   'JUDGE',  '铁面', 60, 55, 15, 50, 70, 20, JSON_ARRAY('生死簿','勾魂笔')),
  -- 忘川桥：孟婆（阴晴不定），望乡客（彷徨）
  ('mengpo_god',  '孟婆',         'BREW',   '阴晴', 45, 38,  8, 42, 50, 60, JSON_ARRAY('忘情汤','回魂引')),
  ('wangxiang_ghost', '望乡客',   'GHOST',  '彷徨', 18, 22,  5, 25, 30, 40, JSON_ARRAY()),
  -- 黄泉渡口：摆渡人（沉默），守泉鬼（凶煞）
  ('ferryman',    '无名·摆渡',    'FERRY',  '沉默', 25, 28, 20, 30, 25, 70, JSON_ARRAY('引魂灯','冥橹')),
  ('shouquan_ghost', '守泉鬼',   'GHOST',  '凶煞', 15, 12, 30,  8, 10, 80, JSON_ARRAY());

-- 6.5.2 game_scene：扩 3 个新场景（原 scene_fengdu 保留）
INSERT INTO `game_scene`
  (`scene_id`, `session_id`, `location_name`, `coord_x`, `coord_y`, `present_gods`, `scene_summary`, `log_count`)
VALUES
  ('scene_bian',     'sess_demo_001', '彼岸渡口', 0, 10,
   '["river_god","judge_god"]',
   '彼岸渡口，水雾苍茫。河伯洛川分水而立，判官崔珏捧生死簿立于渡口石台之上。', 0),
  ('scene_wangchuan', 'sess_demo_001', '忘川桥', -10, 10,
   '["mengpo_god","wangxiang_ghost"]',
   '忘川桥畔，彼岸花开成海。孟婆支汤炉，望乡客徘徊桥头，频频回望人间方向。', 0),
  ('scene_huangquan', 'sess_demo_001', '黄泉渡口', -10, 20,
   '["ferryman","shouquan_ghost"]',
   '黄泉渡口，幽火荧荧。摆渡人无名握冥橹立于古舟，守泉鬼蹲踞渡口石阶，目光凶煞。', 0);

-- 6.5.3 scene_connection：场景连接图（模板级，跨 session 复用）
--   丰渡 → 北去彼岸 → 彼岸 → 西去忘川 → 忘川 → 南下黄泉 → 黄泉
--   双向通行（回来路径）；部分出口需要物品或目标完成
INSERT INTO `scene_connection`
  (`from_scene_id`, `to_scene_id`, `exit_name`, `required_item`, `required_objective`, `note`)
VALUES
  -- 丰渡 ↔ 彼岸：双向通行，无要求
  ('scene_fengdu', 'scene_bian',     '北去彼岸',   NULL, NULL, '北行十里，水雾尽头即彼岸渡口。'),
  ('scene_bian',   'scene_fengdu',   '南返丰渡',   NULL, NULL, '南返水岸，雨师尚在凭栏。'),
  -- 彼岸 → 忘川：需先问过判官（required_objective = talk_to_judge）
  ('scene_bian',   'scene_wangchuan','西去忘川',   NULL, 'talk_to_judge', '判官允过生死簿，方可西行忘川。'),
  -- 忘川 → 彼岸：回程无门禁
  ('scene_wangchuan','scene_bian',   '东返彼岸',   NULL, NULL, '东返渡口，水雾依旧。'),
  -- 忘川 → 黄泉：需饮孟婆忘情汤一杯（required_item = 忘情汤）
  ('scene_wangchuan','scene_huangquan','南下黄泉', '忘情汤', NULL, '饮汤忘情，方可南渡黄泉。'),
  -- 黄泉 → 忘川：回程需引魂灯
  ('scene_huangquan','scene_wangchuan','北返忘川', '引魂灯', NULL, '无引魂灯则迷于幽冥，不可北返。');

-- 6.5.4 player_objective：玩家初始目标（按 session 隔离）
INSERT INTO `player_objective`
  (`objective_id`, `player_id`, `session_id`, `title`, `status`, `progress`, `current_step`)
VALUES
  -- 主线：查涝灾真凶（揭穿博物叟）
  ('investigate_flood', 'p_demo', 'sess_demo_001', '查涝灾真凶，揭穿幕后黑手', 'ACTIVE', 0,
   '玩家抵达丰渡水岸，闻博物叟暗示，雨神疑为涝灾元凶。需多方查证。'),
  -- 支线1：向判官请教生死簿（解锁西去忘川的通行证）
  ('talk_to_judge',     'p_demo', 'sess_demo_001', '向判官崔珏请教生死簿',     'ACTIVE', 0,
   '彼岸渡口，判官掌生死簿，可查证雨神与博物叟之旧怨。'),
  -- 支线2：取得孟婆忘情汤（解锁南下黄泉通行证）
  ('obtain_mengpo_soup','p_demo', 'sess_demo_001', '向孟婆求取忘情汤',        'ACTIVE', 0,
   '忘川桥畔，孟婆掌忘情汤，需与其交涉方能南下黄泉。');

-- 6.5.5 god_relationship：扩新场景内关系网（按 session 隔离）
INSERT INTO `god_relationship`
  (`session_id`, `source_id`,    `target_id`,    `trust_value`, `is_allied`, `is_backstabbed`, `note`)
VALUES
  -- 彼岸：河伯 ↔ 判官（公事往来，无深交）
  ('sess_demo_001', 'river_god',   'judge_god',      45, 0, 0, '河伯与判官公事往来，敬而远之。'),
  ('sess_demo_001', 'judge_god',  'river_god',      50, 0, 0, '判官视河伯为同僚，无偏私。'),
  ('sess_demo_001', 'player_01',  'river_god',      35, 0, 0, '玩家初见河伯，闻其名而未识其人。'),
  ('sess_demo_001', 'river_god',  'player_01',      30, 0, 0, '河伯视玩家为凡人，无特别印象。'),
  ('sess_demo_001', 'player_01',  'judge_god',      50, 0, 0, '玩家敬畏判官，欲求教生死簿。'),
  ('sess_demo_001', 'judge_god',  'player_01',      40, 0, 0, '判官见玩家骨刃在身，略存戒心。'),
  -- 忘川：孟婆 ↔ 望乡客（彼此戒备）
  ('sess_demo_001', 'mengpo_god', 'wangxiang_ghost',20, 0, 0, '孟婆厌望乡客徘徊不肯饮汤。'),
  ('sess_demo_001', 'wangxiang_ghost','mengpo_god', 15, 0, 0, '望乡客惧孟婆忘情汤，远之。'),
  ('sess_demo_001', 'player_01',  'mengpo_god',     25, 0, 0, '玩家初见孟婆，未晓其汤之妙。'),
  ('sess_demo_001', 'mengpo_god', 'player_01',      35, 0, 0, '孟婆见玩家是生客，未给汤。'),
  -- 黄泉：摆渡人 ↔ 守泉鬼（主仆）
  ('sess_demo_001', 'ferryman',   'shouquan_ghost', 60, 0, 0, '摆渡人使唤守泉鬼守渡口。'),
  ('sess_demo_001', 'shouquan_ghost','ferryman',   70, 0, 0, '守泉鬼畏摆渡人，听其号令。'),
  ('sess_demo_001', 'player_01',  'ferryman',      20, 0, 0, '玩家初见摆渡人，未语一字。'),
  ('sess_demo_001', 'ferryman',   'player_01',     25, 0, 0, '摆渡人未识玩家，不允登舟。'),
  ('sess_demo_001', 'player_01',  'shouquan_ghost',10, 0, 0, '玩家见守泉鬼凶煞，警惕。'),
  ('sess_demo_001', 'shouquan_ghost','player_01',   5, 0, 0, '守泉鬼见玩家生人，欲欺之。'),
  -- 跨场景旧关系：博物叟曾在彼岸与判官有旧（博物叟曾试图贿赂判官改生死簿）
  ('sess_demo_001', 'museum_god', 'judge_god',      20, 0, 0, '博物叟曾贿赂判官改生死簿，未成。'),
  ('sess_demo_001', 'judge_god',  'museum_god',     15, 0, 1, '判官识破博物叟贿赂，记其名于案。');

-- =============================================================================
-- V2 新增表：世界书 + 多智能体 + 角色创建
-- =============================================================================

-- ---------- V2.1 世界书（全局事件记录中枢） ----------
--   核心机制：每回合事件自动写入世界书，按时间顺序排列。
--   所有 API 共享世界书但各自的知识范围不同（限知博弈）。
--   importance 为 #9 压缩/遗忘预留：高重要度事件保留原文，低重要度后期压缩。
--   info_source 为 #8 NPC 个人记忆预留：PERSONAL/INQUIRED/TOLD 三级。
DROP TABLE IF EXISTS `world_book`;
CREATE TABLE `world_book` (
  `is_secret` TINYINT(1) NOT NULL DEFAULT 0,
  `entry_id`      BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                  COMMENT '所属会话/周目ID',
  `round_no`      INT DEFAULT 0                         COMMENT '回合序号（本 session 内递增）',
  `story_time`    VARCHAR(32) DEFAULT NULL             COMMENT '剧情内时间（如"戌时三刻"，不可倒退）',
  `coord_x`      INT DEFAULT 0                         COMMENT '事件发生地点坐标X',
  `coord_y`      INT DEFAULT 0                         COMMENT '事件发生地点坐标Y',
  `location_name` VARCHAR(64) NOT NULL                COMMENT '地点名',
  `event_type`    VARCHAR(32) NOT NULL                 COMMENT '事件类型：MOVE/COMBAT/DIALOGUE/INQUIRY/ITEM_GAIN/ITEM_LOSS/NPC_DEATH/NPC_SPAWN/STORY_MILESTONE/DEATH',
  `actor_ids`     JSON DEFAULT NULL                    COMMENT '参与角色ID数组',
  `event_description` MEDIUMTEXT NOT NULL             COMMENT '事件经过或角色档案历史',
  `state_changes` TEXT DEFAULT NULL                    COMMENT '状态变化摘要（如"雨神trust-15"）',
  `importance`    INT DEFAULT 5                        COMMENT '1-10，用于压缩/遗忘优先级（10=关键转折，1=琐碎移动）',
  `info_source`   VARCHAR(16) DEFAULT 'PERSONAL'       COMMENT '信息来源：PERSONAL=亲历/INQUIRED=打听/TOLD=被告知（#8 NPC个人记忆用）',
  `created_at`   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session_round` (`session_id`, `round_no` DESC),
  KEY `idx_session_location` (`session_id`, `coord_x`, `coord_y`),
  KEY `idx_session_recent` (`session_id`, `created_at` DESC),
  KEY `idx_session_importance` (`session_id`, `importance` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 世界书：全局事件记录中枢（记忆+约束+打听查询源）';

-- ---------- V2.2 NPC 独立目标表 ----------
--   每个 NPC 有自己的秘密目标，驱动其主动行为（不只是被动反应玩家）。
--   多智能体核心：NPC API 基于自己的目标 + 已知信息做决策。
DROP TABLE IF EXISTS `npc_goal`;
CREATE TABLE `npc_goal` (
  `goal_id`        BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                  COMMENT '所属会话/周目ID',
  `god_id`        VARCHAR(32) NOT NULL                  COMMENT 'NPC角色ID',
  `goal_description` TEXT NOT NULL                     COMMENT '目标描述（如"隐瞒涝灾真相"）',
  `priority`      INT DEFAULT 5                         COMMENT '1-10，越高越优先',
  `status`        VARCHAR(16) DEFAULT 'ACTIVE'          COMMENT 'ACTIVE/COMPLETED/FAILED/ABANDONED',
  `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_session_god` (`session_id`, `god_id`),
  KEY `idx_session_status` (`session_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 NPC独立目标表（多智能体：每个NPC有自己的秘密目标）';

-- ---------- V2.3 NPC 个人记忆表 ----------
--   每个 NPC 对同一事件的记忆可能不同（信息不对称）。
--   三级记忆：PERSONAL=亲历(完整)/INQUIRED=打听(可能不完整)/TOLD=被告知(可能错误)。
--   accuracy 为 #8 预留：打听来的可能只有部分，被告知的可能有误。
DROP TABLE IF EXISTS `npc_memory`;
CREATE TABLE `npc_memory` (
  `memory_id`      BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                  COMMENT '所属会话/周目ID',
  `god_id`        VARCHAR(32) NOT NULL                  COMMENT '记忆所属的NPC角色ID',
  `world_entry_id` BIGINT DEFAULT NULL                  COMMENT '关联的世界书记录ID（可为空，表示NPC独立记忆）',
  `memory_type`   VARCHAR(16) NOT NULL                 COMMENT 'PERSONAL=亲历/INQUIRED=打听/TOLD=被告知',
  `memory_content` TEXT NOT NULL                       COMMENT 'NPC记住的内容（可能不完整或有误）',
  `accuracy`      INT DEFAULT 100                      COMMENT '0-100，记忆准确度（亲历=100，打听=60-80，被告知=40-60）',
  `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session_god` (`session_id`, `god_id`, `created_at` DESC),
  KEY `idx_session_god_type` (`session_id`, `god_id`, `memory_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 NPC个人记忆表（信息不对称：每个NPC知道的不同）';

-- ---------- V2.4 天赋表 ----------
--   开局由 LLM 根据三问题答案生成，玩家选择后生效。
--   天赋越强则持有越少（power_level 3=强天赋只给1个，1=弱天赋可给3个）。
DROP TABLE IF EXISTS `talent`;
CREATE TABLE `talent` (
  `talent_id`      BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                  COMMENT '所属会话/周目ID',
  `god_id`        VARCHAR(32) DEFAULT NULL              COMMENT '拥有此天赋的角色ID（玩家选择前为空）',
  `talent_name`   VARCHAR(64) NOT NULL                  COMMENT '天赋名称',
  `talent_description` TEXT NOT NULL                    COMMENT '天赋效果描述',
  `power_level`   INT DEFAULT 1                         COMMENT '1=弱/2=中/3=强（越强持有越少）',
  `is_selected`   TINYINT(1) DEFAULT 0                  COMMENT '是否被玩家选中',
  `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session` (`session_id`),
  KEY `idx_session_god` (`session_id`, `god_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 天赋表（开局LLM生成，玩家选择）';

-- ---------- V2.5 角色创建流程表 ----------
--   存储三问题答案、生成的性格标签、选中的天赋、出生故事。
--   status 流转：QUESTIONS → TALENT_GENERATED → STORY_GENERATED → COMPLETED
DROP TABLE IF EXISTS `character_creation`;
CREATE TABLE `character_creation` (
  `creation_id`    BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id`    VARCHAR(32) NOT NULL                  COMMENT '所属会话/周目ID',
  `player_id`     VARCHAR(32) NOT NULL                  COMMENT '玩家账号ID',
  `view_mode`     VARCHAR(16) NOT NULL DEFAULT 'DREAMER' COMMENT '视角：DREAMER=入梦(限知)/OMNISCIENT=知梦(全知)',
  `question_1`    TEXT NOT NULL                          COMMENT '问题1',
  `answer_1`      TEXT DEFAULT NULL                      COMMENT '玩家回答1',
  `question_2`    TEXT NOT NULL                          COMMENT '问题2',
  `answer_2`      TEXT DEFAULT NULL                      COMMENT '玩家回答2',
  `question_3`    TEXT NOT NULL                          COMMENT '问题3',
  `answer_3`      TEXT DEFAULT NULL                      COMMENT '玩家回答3',
  `personality_tags` JSON DEFAULT NULL                  COMMENT 'LLM生成的性格标签数组',
  `selected_talent_id` BIGINT DEFAULT NULL              COMMENT '玩家选中的天赋ID',
  `birth_story`   TEXT DEFAULT NULL                     COMMENT 'LLM编写的从出生到第一次大事件的故事',
  `status`        VARCHAR(24) NOT NULL DEFAULT 'QUESTIONS' COMMENT 'QUESTIONS/TALENT_GENERATED/STORY_GENERATED/COMPLETED',
  `created_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_session_player` (`session_id`, `player_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 角色创建流程表（三问题→天赋→故事开头）';

-- ---------- V2.6 游戏会话表 ----------
--   管理一次完整游戏的生命周期：开始→进行→死亡→总结→重启。
DROP TABLE IF EXISTS `game_session`;
CREATE TABLE `game_session` (
  `session_id`    VARCHAR(32) PRIMARY KEY              COMMENT '会话/周目ID',
  `player_id`     VARCHAR(32) NOT NULL                  COMMENT '玩家账号ID',
  `view_mode`    VARCHAR(16) NOT NULL DEFAULT 'DREAMER' COMMENT '视角模式',
  `status`       VARCHAR(16) NOT NULL DEFAULT 'CREATING' COMMENT 'CREATING/PLAYING/DEAD/SUMMARIZED',
  `death_summary` TEXT DEFAULT NULL                    COMMENT '主角死亡后的总结叙事',
  `inherit_world` TINYINT(1) DEFAULT 0                 COMMENT '重启时是否继承上一周目世界书',
  `prev_session_id` VARCHAR(32) DEFAULT NULL           COMMENT '继承的上一周目ID',
  `created_at`   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_player` (`player_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 游戏会话生命周期表';

-- =============================================================================
-- 校验用 SQL（执行初始化后，可单独运行以下语句核对）
-- （注释掉，避免本脚本执行时额外输出）
-- =============================================================================
-- -- 历史 V1 六维示例（不再是当前规则）；V2 只读校验见 verify-six-stats-v2.sql。
-- SELECT god_id, god_name, base_dou, base_ben,
--        ROUND(base_dou * (1 + base_ben / 100)) AS actual_dou
-- FROM god_profile;
--
-- -- 在场 JSON 校验
-- SELECT scene_id, location_name, JSON_LENGTH(present_gods) AS gods_count FROM game_scene;
--
-- -- 关系有向性校验（期望：museum→rain trust=30；rain→museum trust=10，不相等）
-- SELECT source_id, target_id, trust_value, is_backstabbed FROM god_relationship
--  WHERE (source_id='museum_god' AND target_id='rain_god')
--     OR (source_id='rain_god'   AND target_id='museum_god');

-- NPC decision tracing and topology seed compatibility
CREATE TABLE IF NOT EXISTS `npc_decision_log` (
  `decision_id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `session_id` VARCHAR(32) NOT NULL,
  `log_id` BIGINT DEFAULT NULL,
  `god_id` VARCHAR(32) NOT NULL,
  `scene_id` VARCHAR(32) NOT NULL,
  `player_input` TEXT,
  `behavior` VARCHAR(16) NOT NULL,
  `speech` TEXT NOT NULL,
  `inner_thought` TEXT NOT NULL,
  `is_lying` TINYINT(1) NOT NULL DEFAULT 0,
  `used_fallback` TINYINT(1) DEFAULT 0,
  `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session_created` (`session_id`, `created_at` DESC),
  KEY `idx_log_god` (`log_id`, `god_id`),
  KEY `idx_session_god` (`session_id`, `god_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
UPDATE scene_connection c JOIN game_scene s ON s.scene_id=c.from_scene_id
SET c.session_id=s.session_id, c.direction=CASE
 WHEN c.exit_name LIKE '北%' THEN 'NORTH' WHEN c.exit_name LIKE '南%' THEN 'SOUTH'
 WHEN c.exit_name LIKE '东%' THEN 'EAST' WHEN c.exit_name LIKE '西%' THEN 'WEST'
 ELSE 'OTHER' END;
ALTER TABLE scene_connection ADD UNIQUE KEY uk_session_from_dir(session_id,from_scene_id,direction),
 ADD KEY idx_session_from(session_id,from_scene_id), ADD KEY idx_session_to(session_id,to_scene_id);
INSERT INTO game_session(session_id,player_id,view_mode,status)
 SELECT s.session_id,p.player_id,p.view_mode,'PLAYING'
 FROM player_profile p JOIN game_scene s ON s.scene_id=p.current_scene_id
 WHERE NOT EXISTS(SELECT 1 FROM game_session gs WHERE gs.session_id=s.session_id);

-- Complete state checkpoints (fresh installations only)
USE bs;
ALTER TABLE game_session ADD COLUMN player_god_id VARCHAR(32) NULL,
 ADD COLUMN current_scene_id VARCHAR(32) NULL, ADD COLUMN current_round INT NOT NULL DEFAULT 0;
ALTER TABLE player_profile ADD COLUMN active_session_id VARCHAR(32) NULL;
ALTER TABLE god_profile ADD COLUMN session_id VARCHAR(32) NULL, ADD INDEX idx_god_session(session_id);
UPDATE game_session gs JOIN player_profile p ON p.player_id=gs.player_id
 JOIN game_scene s ON s.scene_id=p.current_scene_id AND s.session_id=gs.session_id
 SET gs.player_god_id=p.god_id,gs.current_scene_id=p.current_scene_id,
 gs.current_round=GREATEST(COALESCE((SELECT MAX(l.round_no) FROM interaction_log l WHERE l.session_id=gs.session_id),0),
 COALESCE((SELECT MAX(w.round_no) FROM world_book w WHERE w.session_id=gs.session_id),0));
UPDATE player_profile p JOIN game_scene s ON s.scene_id=p.current_scene_id SET p.active_session_id=s.session_id;
UPDATE god_profile g SET g.session_id='sess_demo_001'
 WHERE EXISTS(SELECT 1 FROM game_scene s WHERE s.session_id='sess_demo_001' AND JSON_CONTAINS(s.present_gods,JSON_QUOTE(g.god_id)))
 OR EXISTS(SELECT 1 FROM player_profile p WHERE p.god_id=g.god_id AND p.active_session_id='sess_demo_001');
CREATE TABLE session_checkpoint (
 session_id VARCHAR(32) NOT NULL, round_no INT NOT NULL, label VARCHAR(255) NOT NULL,
 payload JSON NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(session_id,round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE session_npc_alias (
 session_id VARCHAR(32) NOT NULL, alias VARCHAR(64) NOT NULL, god_id VARCHAR(32) NOT NULL,
 template_id VARCHAR(32) NULL, PRIMARY KEY(session_id,alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

USE bs;
CREATE TABLE IF NOT EXISTS auth_token (
 token_hash CHAR(64) NOT NULL PRIMARY KEY,
 player_id VARCHAR(32) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_auth_player(player_id),KEY idx_auth_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive migration: no existing rows or columns are modified.
CREATE TABLE IF NOT EXISTS world_clock (
 session_id VARCHAR(32) PRIMARY KEY,
 tick BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS npc_plan (
 plan_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 session_id VARCHAR(32) NOT NULL,
 god_id VARCHAR(32) NOT NULL,
 origin_scene_id VARCHAR(32) NOT NULL,
 behavior VARCHAR(16) NOT NULL,
 target_god_id VARCHAR(32) NOT NULL DEFAULT '',
 destination_scene_id VARCHAR(32) NOT NULL DEFAULT '',
 purpose VARCHAR(240) NOT NULL,
 created_tick BIGINT NOT NULL,
 due_tick BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
 result VARCHAR(240) NOT NULL DEFAULT '',
 INDEX idx_plan_due(session_id,status,due_tick),
 INDEX idx_plan_actor(session_id,god_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Additive migration; existing saves and formulas remain intact.
CREATE TABLE IF NOT EXISTS role_knowledge (
 knowledge_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 session_id VARCHAR(32) NOT NULL,
 god_id VARCHAR(32) NOT NULL,
 source_ref VARCHAR(96) NOT NULL,
 title VARCHAR(120) NOT NULL,
 content TEXT NOT NULL,
 source_type VARCHAR(16) NOT NULL,
 source_god_id VARCHAR(32) NOT NULL DEFAULT '',
 scene_id VARCHAR(32) NOT NULL,
 location_name VARCHAR(64) NOT NULL,
 learned_tick BIGINT NOT NULL,
 UNIQUE KEY uk_role_fact(session_id,god_id,source_ref),
 INDEX idx_role_knowledge(session_id,god_id,knowledge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Execution checkpoint branch evidence (2026-10-15)
CREATE TABLE IF NOT EXISTS session_checkpoint_archive (
 session_id VARCHAR(128) NOT NULL,
 branch_epoch BIGINT NOT NULL,
 round_no INT NOT NULL,
 payload JSON NOT NULL,
 label VARCHAR(255) NOT NULL,
 archived_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(session_id,branch_epoch,round_no)
);

-- Independent billing evidence survives world rewind.
CREATE TABLE IF NOT EXISTS llm_operation_usage (
 call_id CHAR(36) NOT NULL PRIMARY KEY,
 session_id VARCHAR(32) NOT NULL,
 branch_epoch BIGINT NOT NULL,
 operation_id VARCHAR(80) NOT NULL,
 log_id BIGINT NOT NULL,
 model_name VARCHAR(120) NOT NULL,
 state VARCHAR(16) NOT NULL,
 usage_known BOOLEAN NOT NULL DEFAULT FALSE,
 token_input BIGINT NOT NULL DEFAULT 0,
 token_output BIGINT NOT NULL DEFAULT 0,
 cost_cny DECIMAL(16,8) NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 INDEX idx_usage_operation(session_id,branch_epoch,operation_id),
 INDEX idx_usage_log(session_id,log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
