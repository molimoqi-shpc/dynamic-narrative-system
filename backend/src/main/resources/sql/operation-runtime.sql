CREATE TABLE IF NOT EXISTS game_runtime (
 session_id VARCHAR(128) PRIMARY KEY,
 branch_epoch BIGINT NOT NULL DEFAULT 0,
 revision BIGINT NOT NULL DEFAULT 0,
 story_seconds BIGINT NOT NULL DEFAULT 0,
 fence BIGINT NOT NULL DEFAULT 0,
 active_operation VARCHAR(64) NULL
);
CREATE TABLE IF NOT EXISTS game_operation (
 session_id VARCHAR(128) NOT NULL,
 branch_epoch BIGINT NOT NULL,
 operation_id VARCHAR(64) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 player_id VARCHAR(128) NOT NULL,
 status VARCHAR(24) NOT NULL,
 fence BIGINT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 payload JSON NOT NULL,
 PRIMARY KEY(session_id,branch_epoch,operation_id)
);
CREATE TABLE IF NOT EXISTS game_goal_rule (
 session_id VARCHAR(128) NOT NULL,
 owner_id VARCHAR(128) NOT NULL,
 goal_id VARCHAR(64) NOT NULL,
 rule_json JSON NOT NULL,
 terminal_flag BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(session_id,owner_id,goal_id)
);
