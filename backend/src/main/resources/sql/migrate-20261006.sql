USE bs;
CREATE TABLE IF NOT EXISTS `world_book` (
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
  `event_description` TEXT NOT NULL                   COMMENT '一句话概括发生了什么',
  `state_changes` TEXT DEFAULT NULL                    COMMENT '状态变化摘要（如"雨神trust-15"）',
  `importance`    INT DEFAULT 5                        COMMENT '1-10，用于压缩/遗忘优先级（10=关键转折，1=琐碎移动）',
  `info_source`   VARCHAR(16) DEFAULT 'PERSONAL'       COMMENT '信息来源：PERSONAL=亲历/INQUIRED=打听/TOLD=被告知（#8 NPC个人记忆用）',
  `created_at`   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_session_round` (`session_id`, `round_no` DESC),
  KEY `idx_session_location` (`session_id`, `coord_x`, `coord_y`),
  KEY `idx_session_recent` (`session_id`, `created_at` DESC),
  KEY `idx_session_importance` (`session_id`, `importance` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='V2 世界书：全局事件记录中枢（记忆+约束+打听查询源）';

CREATE TABLE IF NOT EXISTS `npc_goal` (
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

CREATE TABLE IF NOT EXISTS `npc_memory` (
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

CREATE TABLE IF NOT EXISTS `talent` (
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

CREATE TABLE IF NOT EXISTS `character_creation` (
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

CREATE TABLE IF NOT EXISTS `game_session` (
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

ALTER TABLE interaction_log ADD COLUMN round_no INT DEFAULT 0;
ALTER TABLE scene_connection ADD COLUMN session_id VARCHAR(32) DEFAULT NULL,
 ADD COLUMN direction VARCHAR(16) NOT NULL DEFAULT 'OTHER';
UPDATE scene_connection c JOIN game_scene s ON s.scene_id=c.from_scene_id
SET c.session_id=s.session_id, c.direction=CASE
 WHEN c.exit_name LIKE '北%' THEN 'NORTH' WHEN c.exit_name LIKE '南%' THEN 'SOUTH'
 WHEN c.exit_name LIKE '东%' THEN 'EAST' WHEN c.exit_name LIKE '西%' THEN 'WEST'
 ELSE 'OTHER' END;

ALTER TABLE scene_connection ADD UNIQUE KEY uk_session_from_dir(session_id,from_scene_id,direction),
 ADD KEY idx_session_from(session_id,from_scene_id), ADD KEY idx_session_to(session_id,to_scene_id);
UPDATE interaction_log l JOIN
 (SELECT log_id, ROW_NUMBER() OVER(PARTITION BY session_id ORDER BY log_id) AS rn FROM interaction_log) r
 ON r.log_id=l.log_id SET l.round_no=r.rn WHERE l.round_no=0 OR l.round_no IS NULL;
INSERT INTO game_session(session_id,player_id,view_mode,status)
 SELECT s.session_id,p.player_id,p.view_mode,'PLAYING'
 FROM player_profile p JOIN game_scene s ON s.scene_id=p.current_scene_id
 WHERE NOT EXISTS(SELECT 1 FROM game_session gs WHERE gs.session_id=s.session_id);

ALTER TABLE game_scene MODIFY COLUMN present_gods JSON NOT NULL;
