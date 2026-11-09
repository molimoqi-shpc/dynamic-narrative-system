package com.bs.narrative.repository;

import com.bs.narrative.domain.GodRelationship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface GodRelationshipRepository extends JpaRepository<GodRelationship, Long> {

    /**
     * FR-B2.1 一次性加载在场角色两两之间（以及玩家↔在场）的全部有向关系。
     * source ∈ ids 且 target ∈ ids。
     */
    @Query("SELECT r FROM GodRelationship r WHERE r.sourceId IN :ids AND r.targetId IN :ids")
    List<GodRelationship> findBySourceIdInAndTargetIdIn(@Param("ids") Set<String> ids);

    /** 防坑规则3：按 session_id 隔离，加载该会话下 ids 集合内的所有两两有向关系。 */
    @Query("SELECT r FROM GodRelationship r " +
           "WHERE r.sessionId = :sessionId AND r.sourceId IN :ids AND r.targetId IN :ids")
    List<GodRelationship> findBySessionIdAndSourceIdInAndTargetIdIn(@Param("sessionId") String sessionId,
                                                                    @Param("ids") Set<String> ids);

    /** 积木式场景引擎：查某 session 下所有关系（GameActionService 加载关系网用）。 */
    List<GodRelationship> findBySessionId(String sessionId);

    default List<GodRelationship> findAllPairsAmong(String sessionId, Set<String> ids) {
        return findBySessionIdAndSourceIdInAndTargetIdIn(sessionId, ids);
    }

    /** 向后兼容：未指定 session 时退回原行为（仅用于历史测试或单 session 演示）。 */
    default List<GodRelationship> findAllPairsAmong(Set<String> ids) {
        return findBySourceIdInAndTargetIdIn(ids);
    }
}
