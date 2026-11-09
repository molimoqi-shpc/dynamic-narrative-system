package com.bs.narrative.repository;

import com.bs.narrative.domain.SceneConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 场景连接图 Repository（拓扑图边查询）。
 *
 * 【优化指导 6.2】按 session 隔离，支持方向枚举查询。
 * 旧 findByFromSceneId 保留兼容（跨 session 模板）。
 */
public interface SceneConnectionRepository extends JpaRepository<SceneConnection, Long> {

    /** 查询某场景的所有出口（旧路径，跨 session 模板级数据）。 */
    List<SceneConnection> findByFromSceneId(String fromSceneId);

    /** 查询某 session 某场景的所有出口（6.2 主路径，按会话隔离）。 */
    List<SceneConnection> findBySessionIdAndFromSceneId(String sessionId, String fromSceneId);

    /** 查询某 session 某场景某方向的出口（6.2 主路径，玩家"往北走"时用）。 */
    Optional<SceneConnection> findBySessionIdAndFromSceneIdAndDirection(
            String sessionId, String fromSceneId, String direction);

    /** 查询某 session 某场景的所有出口（含 direction 字段，前端出口列表用）。 */
    List<SceneConnection> findBySessionIdAndFromSceneIdOrderByDirectionAsc(
            String sessionId, String fromSceneId);

    /** 查询指向某场景的所有边（用于反向边建立时检查是否已存在）。 */
    List<SceneConnection> findBySessionIdAndToSceneId(String sessionId, String toSceneId);
}
