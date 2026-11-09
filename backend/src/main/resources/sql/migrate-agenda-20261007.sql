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
