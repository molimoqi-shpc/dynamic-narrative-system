-- Story references are archival aids. No trigger, location or time condition decides the narrative.
CREATE TABLE IF NOT EXISTS story_plot_event (
 session_id VARCHAR(128) NOT NULL,
 event_id VARCHAR(40) NOT NULL,
 title VARCHAR(240) NOT NULL,
 category VARCHAR(32) NOT NULL DEFAULT 'OTHER',
 status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
 record_json JSON NOT NULL,
 player_view JSON NULL,
 source_log_id BIGINT NOT NULL,
 source_frame INT NOT NULL,
 updated_seconds BIGINT NOT NULL,
 round_no INT NOT NULL,
 PRIMARY KEY(session_id,event_id),
 INDEX idx_plot_event_recent(session_id,updated_seconds,source_log_id)
);
CREATE TABLE IF NOT EXISTS story_foreshadow (
 session_id VARCHAR(128) NOT NULL,
 thread_id VARCHAR(40) NOT NULL,
 event_id VARCHAR(40) NOT NULL,
 title VARCHAR(240) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
 record_json JSON NOT NULL,
 player_view JSON NULL,
 source_log_id BIGINT NOT NULL,
 source_frame INT NOT NULL,
 updated_seconds BIGINT NOT NULL,
 round_no INT NOT NULL,
 PRIMARY KEY(session_id,thread_id),
 INDEX idx_foreshadow_event(session_id,event_id),
 CONSTRAINT fk_foreshadow_plot_event FOREIGN KEY(session_id,event_id)
   REFERENCES story_plot_event(session_id,event_id) ON DELETE CASCADE
);
