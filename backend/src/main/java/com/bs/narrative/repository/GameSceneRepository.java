package com.bs.narrative.repository;

import com.bs.narrative.domain.GameScene;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameSceneRepository extends JpaRepository<GameScene, String> {

    java.util.List<GameScene> findBySessionId(String sessionId);

    /** 防坑规则3：按 session_id 隔离查询场景，避免多玩家互相覆盖。 */
    Optional<GameScene> findBySessionIdAndSceneId(String sessionId, String sceneId);
}
