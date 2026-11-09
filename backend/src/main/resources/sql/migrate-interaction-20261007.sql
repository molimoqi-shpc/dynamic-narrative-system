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
