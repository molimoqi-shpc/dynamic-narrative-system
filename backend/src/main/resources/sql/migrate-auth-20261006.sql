USE bs;
CREATE TABLE IF NOT EXISTS auth_token (
 token_hash CHAR(64) NOT NULL PRIMARY KEY,
 player_id VARCHAR(32) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_auth_player(player_id),KEY idx_auth_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
