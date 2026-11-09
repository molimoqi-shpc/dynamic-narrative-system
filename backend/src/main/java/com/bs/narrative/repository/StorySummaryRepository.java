package com.bs.narrative.repository;

import com.bs.narrative.domain.StorySummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 剧情总结元数据 Repository（隐藏锚点 + 坐标定位 + Prompt 注入）。
 *
 * 三类查询场景：
 *   1) 注入 Prompt 用：查某 session 最近 N 条总结（按 round_no 降序）
 *   2) 玩家回去定位用：按坐标查已存在的场景总结
 *   3) 异步摘要用：查某 session 某场景的所有总结（B2 用）
 */
public interface StorySummaryRepository extends JpaRepository<StorySummary, Long> {

    /** 注入 Prompt 用：查某 session 最近 N 条总结（按 round_no 降序）。 */
    List<StorySummary> findBySessionIdOrderByRoundNoDesc(String sessionId, Pageable pageable);

    /** 玩家回去定位用：按坐标查已存在的场景总结。 */
    Optional<StorySummary> findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc(
            String sessionId, Integer coordX, Integer coordY);

    /** 异步摘要用：查某 session 某场景的所有总结。 */
    List<StorySummary> findBySessionIdAndSceneIdOrderByRoundNoAsc(String sessionId, String sceneId);
}
