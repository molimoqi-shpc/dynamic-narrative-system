package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.StorySummary;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.Pass2Service;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.repository.StorySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 剧情总结元数据服务：负责三类核心操作。
 *
 * 1) buildContextForPrompt：拼"已知世界信息"文本，注入 Pass1/Pass2 Prompt 约束 LLM
 *    - 查同 session 最近 N 条 story_summary（按 round_no 降序）
 *    - 格式化成 LLM 可读的"已确立事实清单"
 *    - LLM 必须基于这些事实，不能编造与已记录地点/时间/剧情矛盾的内容
 *
 * 2) persistSummary：把 LLM 输出的 hidden_summary 落库
 *    - 自动分配 round_no（本 session 内递增）
 *    - 关联 scene_id + 坐标
 *
 * 3) findExistingSceneByCoord：玩家"回去"时按坐标定位已存在场景
 *    - 避免回到同一地点 LLM 又取新名字
 */
@Service
public class StorySummaryService {

    private static final Logger log = LoggerFactory.getLogger(StorySummaryService.class);

    /** 注入 Prompt 时查最近多少条总结（短记忆 + 中期锚点）。 */
    private static final int INJECT_TOP_N = 5;

    private final StorySummaryRepository summaryRepo;
    private final GameSceneRepository sceneRepo;

    public StorySummaryService(StorySummaryRepository summaryRepo,
                               GameSceneRepository sceneRepo) {
        this.summaryRepo = summaryRepo;
        this.sceneRepo = sceneRepo;
    }

    /**
     * 拼"已知世界信息"文本，注入 Pass1/Pass2 Prompt。
     * 格式：按 round 顺序列出已确立的地点/时间/剧情/状态变化。
     */
    @Transactional(readOnly = true)
    public String buildContextForPrompt(String sessionId) {
        List<StorySummary> recent = summaryRepo.findBySessionIdOrderByRoundNoDesc(
                sessionId, PageRequest.of(0, INJECT_TOP_N));
        if (recent == null || recent.isEmpty()) {
            return "（暂无已确立的剧情锚点，这是本 session 第一回合）";
        }
        // 按 round 升序展示（正序时间线，便于 LLM 理解）
        StringBuilder sb = new StringBuilder();
        sb.append("以下是本会话已确立的剧情锚点（必须基于这些事实，禁止编造矛盾内容）：\n");
        for (int i = recent.size() - 1; i >= 0; i--) {
            StorySummary s = recent.get(i);
            sb.append("【回合").append(s.getRoundNo()).append("】");
            sb.append("地点=").append(s.getLocationName())
                    .append("(坐标").append(s.getCoordX()).append(",").append(s.getCoordY()).append(")");
            if (s.getStoryTime() != null && !s.getStoryTime().isBlank()) {
                sb.append(" 时间=").append(s.getStoryTime());
            }
            sb.append("\n  剧情：").append(s.getPlotSummary());
            if (s.getStateChanges() != null && !s.getStateChanges().isBlank()
                    && !"无".equals(s.getStateChanges())) {
                sb.append("\n  状态变化：").append(s.getStateChanges());
            }
            sb.append("\n");
        }
        sb.append("【地点连续性约束】已存在的地点名 + 坐标已记录，玩家回到这些地点时必须用原名 + 原坐标，");
        sb.append("禁止自创新名。新地点必须避开已占用坐标。");
        return sb.toString();
    }

    /**
     * 落库 hidden_summary：自动分配 round_no + 关联场景 + 坐标冲突校验 + 并发重试。
     *
     * 坐标冲突规则（P4）：
     *   - 同 session 同坐标必须对应同一场景（sceneId 相同）
     *   - 同坐标不同场景 → 抛 COORD_CONFLICT（LLM 可能编造了与已存在场景冲突的坐标）
     *   - 同坐标同场景 → 允许（同一场景多次回合记录）
     *   - 同坐标但 locationName 不同 → 抛 COORD_CONFLICT（地点名不一致）
     *
     * 并发控制（P2）：
     *   - story_summary 表有 UNIQUE KEY (session_id, round_no)
     *   - save 时若 round_no 冲突（并发竞态），捕获 DataIntegrityViolationException 后重试 1 次
     *   - 重试仍失败则抛业务异常，由调用方决定是否 Fallback
     *
     * @throws BusinessException 坐标冲突 / 并发重试失败
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StorySummary persistSummary(String sessionId,
                                       String sceneId,
                                       Pass2Service.HiddenSummary hs) {
        if (hs == null) {
            log.warn("[StorySummary] hidden_summary 为空，跳过落库 sessionId={}", sessionId);
            return null;
        }

        int coordX = hs.coordX() == null ? 0 : hs.coordX();
        int coordY = hs.coordY() == null ? 0 : hs.coordY();
        String locationName = hs.locationName() == null ? "未知地点" : hs.locationName();

        // Coordinates and names are engine facts, never model-authoritative metadata.
        var canonical=sceneRepo.findBySessionIdAndSceneId(sessionId,sceneId);
        if(canonical.isPresent()) {
            coordX=canonical.get().getCoordX(); coordY=canonical.get().getCoordY();
            locationName=canonical.get().getLocationName();
        }
        // 1) 坐标冲突校验：查同 session 同坐标的已存在场景
        validateCoordConflict(sessionId, sceneId, coordX, coordY, locationName);

        // 2) 构造实体（round_no 在 doPersist 里分配，便于重试）
        StorySummary s = buildEntity(sessionId, sceneId, hs, coordX, coordY, locationName);

        // 3) 落库 + 并发重试（最多 1 次）
        try {
            return doPersistWithRetry(sessionId, s);
        } catch (DataIntegrityViolationException e) {
            log.warn("[StorySummary] round_no 并发冲突，重试一次仍失败 sessionId={} msg={}",
                    sessionId, e.getMessage());
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "story_summary round_no 并发冲突，重试失败，请稍后重试", true, e);
        }
    }

    /** The session row is already locked by SessionStateService.begin. A failed write must roll back the entire turn. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StorySummary persistSummaryAtRound(String sessionId, String sceneId, int round,
                                               Pass2Service.HiddenSummary facts) {
        GameScene scene = sceneRepo.findBySessionIdAndSceneId(sessionId, sceneId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_INVALID, "总结场景不存在"));
        StorySummary summary = buildEntity(sessionId, sceneId, facts,
                scene.getCoordX(), scene.getCoordY(), scene.getLocationName());
        summary.setRoundNo(round);
        return summaryRepo.saveAndFlush(summary);
    }

    /** 坐标冲突校验（P4）。 */
    private void validateCoordConflict(String sessionId, String sceneId,
                                       int coordX, int coordY, String locationName) {
        Optional<StorySummary> existingByCoord = summaryRepo
                .findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc(sessionId, coordX, coordY);
        if (existingByCoord.isPresent()) {
            StorySummary exist = existingByCoord.get();
            // 同坐标不同场景 → 冲突
            if (exist.getSceneId() != null && !exist.getSceneId().equals(sceneId)) {
                throw new BusinessException(ErrorCode.COORD_CONFLICT,
                        String.format("坐标(%d,%d)已被场景[%s]占用，不能再分配给场景[%s]",
                                coordX, coordY, exist.getSceneId(), sceneId));
            }
            // 同坐标同场景但地点名不同 → 冲突（LLM 给同一地点取了不同名字）
            if (!exist.getLocationName().equals(locationName)) {
                throw new BusinessException(ErrorCode.COORD_CONFLICT,
                        String.format("坐标(%d,%d)已有地点名[%s]，LLM 输出[%s]不一致",
                                coordX, coordY, exist.getLocationName(), locationName));
            }
        }
    }

    /** 构造 StorySummary 实体（不分配 round_no）。 */
    private StorySummary buildEntity(String sessionId, String sceneId,
                                     Pass2Service.HiddenSummary hs,
                                     int coordX, int coordY, String locationName) {
        StorySummary s = new StorySummary();
        s.setSessionId(sessionId);
        s.setSceneId(sceneId);
        s.setCoordX(coordX);
        s.setCoordY(coordY);
        s.setLocationName(locationName);
        s.setStoryTime(hs.storyTime());
        s.setPlotSummary(hs.plotSummary() == null ? "" : hs.plotSummary());
        s.setPresentGods(hs.presentGods());
        s.setKeyEvents(hs.keyEvents());
        s.setStateChanges(hs.stateChanges());
        return s;
    }

    /**
     * 分配 round_no 并落库，冲突时重试 1 次。
     * 用 UNIQUE KEY (session_id, round_no) 做最后防线。
     */
    private StorySummary doPersistWithRetry(String sessionId, StorySummary s) {
        for (int attempt = 0; attempt < 2; attempt++) {
            s.setRoundNo(nextRoundNo(sessionId));
            try {
                StorySummary saved = summaryRepo.saveAndFlush(s);
                log.info("[StorySummary] 落库成功 sessionId={} round={} sceneId={} coord=({},{}) location={}",
                        sessionId, saved.getRoundNo(), saved.getSceneId(),
                        saved.getCoordX(), saved.getCoordY(), saved.getLocationName());
                return saved;
            } catch (DataIntegrityViolationException e) {
                if (attempt == 0) {
                    log.info("[StorySummary] round_no 冲突，重试分配 sessionId={}", sessionId);
                    continue;
                }
                throw e;
            }
        }
        throw new DataIntegrityViolationException("round_no 分配重试耗尽");
    }

    /** 分配下一个 round_no（本 session 最大 round + 1）。 */
    private int nextRoundNo(String sessionId) {
        List<StorySummary> recent = summaryRepo.findBySessionIdOrderByRoundNoDesc(
                sessionId, PageRequest.of(0, 1));
        if (recent == null || recent.isEmpty()) return 1;
        Integer max = recent.get(0).getRoundNo();
        return max == null ? 1 : max + 1;
    }

    /**
     * 玩家"回去"时按坐标定位已存在场景。
     * - 先查 story_summary 表（同 session + 同坐标 + 最近一回合）
     * - 拿到 scene_id 后查 game_scene
     * - 返回已存在的 GameScene，避免 LLM 重复取名
     */
    @Transactional(readOnly = true)
    public Optional<GameScene> findExistingSceneByCoord(String sessionId, Integer coordX, Integer coordY) {
        Optional<StorySummary> recent = summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc(
                sessionId, coordX, coordY);
        if (recent.isEmpty() || recent.get().getSceneId() == null) {
            var scenes=sceneRepo.findBySessionId(sessionId).stream()
                    .filter(s->Objects.equals(coordX,s.getCoordX()) && Objects.equals(coordY,s.getCoordY())).toList();
            if(scenes.size()>1) throw new BusinessException(ErrorCode.COORD_CONFLICT,"坐标对应多个地点，请使用场景ID");
            return scenes.stream().findFirst();
        }
        return sceneRepo.findById(recent.get().getSceneId());
    }
}
