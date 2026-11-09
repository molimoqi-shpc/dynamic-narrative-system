package com.bs.narrative.repository;

import com.bs.narrative.domain.InteractionLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InteractionLogRepository extends JpaRepository<InteractionLog, Long> {

    /** 防坑规则3：按 session_id 隔离的短记忆（最近 3 轮，FR-C2 强制只传 3 条）。 */
    List<InteractionLog> findTop3BySessionIdAndPlayerIdAndSceneIdOrderByCreatedAtDesc(String sessionId,
                                                                                      String playerId,
                                                                                      String sceneId);

    /** 防坑规则3：按 session_id 隔离的历史分页。 */
    List<InteractionLog> findBySessionIdAndPlayerIdAndSceneIdOrderByCreatedAtDesc(String sessionId,
                                                                                   String playerId,
                                                                                   String sceneId,
                                                                                   Pageable pageable);

    /** 防坑规则3：某会话某场景累计轮数（用于 scene_summary 每 10 轮触发异步摘要）。 */
    long countBySessionIdAndSceneId(String sessionId, String sceneId);

    // —— 6.4 事件溯源：按 round_no 查询/删除 ——

    /** 查某 session 最大 round_no（事件溯源用）。 */
    @Query("SELECT MAX(l.roundNo) FROM InteractionLog l WHERE l.sessionId = :sid")
    Integer findMaxRoundNo(@Param("sid") String sessionId);

    /** 查某 session 某 round_no 的日志（事件溯源回放用）。 */
    List<InteractionLog> findBySessionIdAndRoundNoOrderByLogIdAsc(String sessionId, Integer roundNo);

    /**
     * 【优化指导 6.4】删除某 session 中 round_no > 指定回合的所有日志。
     * 读档到第 N 回合时调用，实现轻量级时空回溯。
     */
    @Modifying
    @Query("DELETE FROM InteractionLog l WHERE l.sessionId = :sid AND l.roundNo > :round")
    int deleteBySessionIdAndRoundNoGreaterThan(@Param("sid") String sessionId, @Param("round") Integer roundNo);

    // —— 向后兼容：历史单 session 演示路径 ——
    /** 短记忆：最近 3 轮（FR-C2 强制只传 3 条）。 */
    default List<InteractionLog> findTop3ByPlayerIdAndSceneIdOrderByCreatedAtDesc(String playerId, String sceneId) {
        return findTop3BySessionIdAndPlayerIdAndSceneIdOrderByCreatedAtDesc("sess_demo_001", playerId, sceneId);
    }

    /** 历史分页。 */
    default List<InteractionLog> findByPlayerIdAndSceneIdOrderByCreatedAtDesc(String playerId, String sceneId, Pageable pageable) {
        return findBySessionIdAndPlayerIdAndSceneIdOrderByCreatedAtDesc("sess_demo_001", playerId, sceneId, pageable);
    }

    /** 某场景累计轮数（用于 scene_summary 每 10 轮触发异步摘要）。 */
    default long countBySceneId(String sceneId) {
        return countBySessionIdAndSceneId("sess_demo_001", sceneId);
    }
}
