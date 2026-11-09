CREATE TABLE IF NOT EXISTS session_checkpoint_archive (
 session_id VARCHAR(128) NOT NULL,
 branch_epoch BIGINT NOT NULL,
 round_no INT NOT NULL,
 payload JSON NOT NULL,
 label VARCHAR(255) NOT NULL,
 archived_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(session_id,branch_epoch,round_no)
);
