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
