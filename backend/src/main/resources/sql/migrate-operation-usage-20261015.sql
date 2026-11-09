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
