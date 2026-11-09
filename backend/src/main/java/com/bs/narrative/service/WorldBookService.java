package com.bs.narrative.service;

import com.bs.narrative.domain.WorldBook;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.repository.WorldBookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * V2 世界书服务：全局事件记录中枢。
 *
 * 核心职责：
 *   1) addEvent：每回合事件写入世界书（含回合号、时间、地点、参与者、事件描述、重要度）
 *   2) buildContextForPrompt：拼"已知世界信息"文本，注入 LLM Prompt 约束叙事连续性
 *   3) queryByLocation：按地点坐标查询事件（打听机制的查询源）
 *   4) queryForInquiry：按地点筛选世界书 → 随机选取一部分（打听机制核心，限知博弈）
 *   5) queryByCharacter：按角色查询参与过的事件
 *   6) getVisitedLocations：获取已访问过的所有地点（供坐标回去 UI）
 *
 * 与 StorySummaryService 的关系：
 *   StorySummaryService 仍负责 story_summary 表的落库和坐标校验（防坑机制保留）。
 *   WorldBookService 负责世界书的事件记录和查询，是新增的多智能体/打听/记忆系统的基础设施。
 *   两者并行：story_summary 保留坐标唯一约束和防坑功能，world_book 提供更丰富的事件查询。
 */
@Service
public class WorldBookService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final Logger log = LoggerFactory.getLogger(WorldBookService.class);

    /** 注入 Prompt 时查最近多少条世界书记录。 */
    private static final int INJECT_TOP_N = 10;

    /** 打听时最多返回多少条事件。 */
    private static final int INQUIRY_MAX_RESULTS = 5;

    private final WorldBookRepository worldBookRepo;
    private final SessionStateService stateService;
    private final GameSceneRepository sceneRepo;

    public WorldBookService(WorldBookRepository worldBookRepo,
                             GameSceneRepository sceneRepo, SessionStateService stateService) {
        this.worldBookRepo = worldBookRepo;
        this.stateService=stateService;
        this.sceneRepo = sceneRepo;
    }

    // ==================== 写入 ====================

    /**
     * 写入一条世界书事件。
     *
     * @param sessionId      会话ID
     * @param roundNo        回合号
     * @param storyTime      剧情内时间
     * @param coordX        坐标X
     * @param coordY        坐标Y
     * @param locationName   地点名
     * @param eventType      事件类型（MOVE/COMBAT/DIALOGUE/INQUIRY/ITEM_GAIN/ITEM_LOSS/NPC_DEATH/NPC_SPAWN/STORY_MILESTONE/DEATH）
     * @param actorIds       参与角色ID列表
     * @param eventDescription 事件描述
     * @param stateChanges    状态变化摘要
     * @param importance      重要度（1-10，默认5）
     * @return 保存后的 WorldBook 实体
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorldBook addEvent(String sessionId, int roundNo, String storyTime,
                              int coordX, int coordY, String locationName,
                              String eventType, List<String> actorIds,
                              String eventDescription, String stateChanges,
                              int importance) {
        return addEvent(sessionId, roundNo, storyTime, coordX, coordY, locationName,
                eventType, actorIds, eventDescription, stateChanges, importance, false);
    }

    /**
     * 写入一条世界书事件（含 is_secret 字段，6.3 加权采样用）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorldBook addEvent(String sessionId, int roundNo, String storyTime,
                              int coordX, int coordY, String locationName,
                              String eventType, List<String> actorIds,
                              String eventDescription, String stateChanges,
                              int importance, boolean isSecret) {
        WorldBook entry = new WorldBook();
        entry.setSessionId(sessionId);
        entry.setRoundNo(roundNo);
        entry.setStoryTime(storyTime);
        entry.setCoordX(coordX);
        entry.setCoordY(coordY);
        entry.setLocationName(locationName);
        entry.setEventType(eventType);
        entry.setActorIds(actorIds);
        entry.setEventDescription(eventDescription);
        entry.setStateChanges(stateChanges);
        entry.setImportance(Math.max(1, Math.min(10, importance)));
        entry.setIsSecret(isSecret);
        entry.setInfoSource("PERSONAL");

        WorldBook saved = worldBookRepo.save(entry);
        log.info("[WorldBook] 写入 sessionId={} round={} type={} location={} importance={} isSecret={}",
                sessionId, roundNo, eventType, locationName, importance, isSecret);
        return saved;
    }

    /**
     * 快捷写入：默认重要度=5，非秘密。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorldBook addEvent(String sessionId, int roundNo, String storyTime,
                              int coordX, int coordY, String locationName,
                              String eventType, List<String> actorIds,
                              String eventDescription, String stateChanges) {
        return addEvent(sessionId, roundNo, storyTime, coordX, coordY, locationName,
                eventType, actorIds, eventDescription, stateChanges, 5, false);
    }

    // ==================== 读取：Prompt 注入 ====================

    /**
     * 拼"已知世界信息"文本，注入 LLM Prompt 约束叙事连续性。
     * 格式：按 round 顺序列出已确立的事件，LLM 必须基于这些事实。
     */
    @Transactional(readOnly = true)
    public String buildContextForPrompt(String sessionId) {
        List<WorldBook> recent = worldBookRepo.findBySessionIdOrderByRoundNoDesc(
                sessionId, PageRequest.of(0, INJECT_TOP_N));
        if (recent == null || recent.isEmpty()) {
            return "（暂无已确立的世界书记录，这是本 session 第一回合）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是本会话世界书中已确立的事件（必须基于这些事实，禁止编造矛盾内容）：\n");
        // 按 round 升序展示（正序时间线）
        for (int i = recent.size() - 1; i >= 0; i--) {
            WorldBook w = recent.get(i);
            sb.append("【回合").append(w.getRoundNo()).append("】");
            if (w.getStoryTime() != null && !w.getStoryTime().isBlank()) {
                sb.append("时间=").append(w.getStoryTime()).append(" ");
            }
            sb.append("地点=").append(w.getLocationName())
                    .append("(坐标").append(w.getCoordX()).append(",").append(w.getCoordY()).append(")");
            sb.append("\n  事件：").append(w.getEventDescription());
            if (w.getStateChanges() != null && !w.getStateChanges().isBlank()) {
                sb.append("\n  变化：").append(w.getStateChanges());
            }
            sb.append("\n");
        }
        sb.append("【地点连续性约束】已存在的地点名 + 坐标已记录，玩家回到这些地点时必须用原名 + 原坐标，");
        sb.append("禁止自创新名。新地点必须避开已占用坐标。");
        return sb.toString();
    }

    // ==================== 读取：打听机制 ====================

    /**
     * 打听机制核心：按地点坐标筛选世界书 → 随机选取一部分。
     *
     * 限知博弈的关键：角色只能知道自己在某地点"打听"到的信息，不是全部。
     * 两个角色在同一地点可能知道不同的事。
     *
     * @param sessionId  会话ID
     * @param coordX    坐标X
     * @param coordY    坐标Y
     * @return 随机选取的事件列表（最多 INQUIRY_MAX_RESULTS 条）
     */
    @Transactional(readOnly = true)
    public List<WorldBook> queryForInquiry(String sessionId, int coordX, int coordY) {
        List<WorldBook> all = worldBookRepo.findBySessionIdAndCoordXAndCoordYOrderByRoundNoAsc(
                sessionId, coordX, coordY);
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        // 随机选取一部分（不是全部）
        List<WorldBook> shuffled = new ArrayList<>(all.stream().filter(w -> Boolean.FALSE.equals(w.getIsSecret())).toList());
        Collections.shuffle(shuffled);
        int count = Math.min(INQUIRY_MAX_RESULTS, shuffled.size());
        // 但保留按 round 升序的展示
        List<WorldBook> selected = shuffled.subList(0, count);
        selected.sort(Comparator.comparing(WorldBook::getRoundNo));
        return selected;
    }

    /**
     * 【优化指导 6.3】属性驱动的加权采样打听。
     *
     * 核心改进（从纯随机升级）：
     *   - 闻与慧影响公开候选的信息筛选和权重，不授予秘密访问权限
     *   - 低属性只能捞出 importance≤2 的表面记录
     *   - 采样不再是纯随机，而是 importance 加权（高重要度事件更可能被抽中）
     *
     * 这把六维数值引擎与打听机制完美咬合。
     *
     * @param sessionId  会话ID
     * @param coordX     地点坐标X
     * @param coordY     地点坐标Y
     * @param wenStat    打听者"闻"（感知）数值
     * @param huiStat    打听者"慧"（推理）数值
     * @return 加权采样后的事件列表（最多 INQUIRY_MAX_RESULTS 条）
     */
    @Transactional(readOnly = true)
    public List<WorldBook> queryForInquiryWeighted(String sessionId, int coordX, int coordY,
                                                     int wenStat, int huiStat) {
        List<WorldBook> all = worldBookRepo.findBySessionIdAndCoordXAndCoordYOrderByRoundNoAsc(
                sessionId, coordX, coordY);
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        // 1) 属性门槛过滤：决定能"看到"哪些事件
        //    V2 weighted actual perception; secrets additionally require actual reasoning.
        double perception = com.bs.narrative.engine.StatsCalculator.perception(wenStat,huiStat);
        int maxImportanceVisible = com.bs.narrative.engine.StatsCalculator.inquiryImportance(wenStat,huiStat);
        // Attributes affect sampling, never grant access to undisclosed private events.

        List<WorldBook> visible = new ArrayList<>();
        for (WorldBook w : all) {
            // Unknown visibility is not public; private disclosures require an actual source.
            if (!Boolean.FALSE.equals(w.getIsSecret())) {
                continue;
            }
            // 高重要度事件需高属性才能看到
            int imp = w.getImportance() == null ? 5 : w.getImportance();
            if (imp > maxImportanceVisible) {
                continue;
            }
            visible.add(w);
        }

        if (visible.isEmpty()) {
            log.info("[Inquiry-6.3] 无可见事件 sessionId={} coord=({},{}) perception={} maxImp={}",
                    sessionId, coordX, coordY, perception, maxImportanceVisible);
            return List.of();
        }

        // 2) 加权采样：importance 越高被抽中概率越大
        //    权重 = importance^2（高重要度事件被优先抽中）
        List<WorldBook> pool = new ArrayList<>(visible);
        List<WorldBook> selected = new ArrayList<>();
        Random rng = new Random();
        int targetCount = Math.min(INQUIRY_MAX_RESULTS, pool.size());

        while (!pool.isEmpty() && selected.size() < targetCount) {
            // 计算总权重
            double totalWeight = 0;
            for (WorldBook w : pool) {
                int imp = w.getImportance() == null ? 5 : w.getImportance();
                totalWeight += Math.pow(imp, 2);
            }
            // 加权随机选一个
            double r = rng.nextDouble() * totalWeight;
            double cumulative = 0;
            int pickedIdx = 0;
            for (int i = 0; i < pool.size(); i++) {
                int imp = pool.get(i).getImportance() == null ? 5 : pool.get(i).getImportance();
                cumulative += Math.pow(imp, 2);
                if (r <= cumulative) {
                    pickedIdx = i;
                    break;
                }
            }
            selected.add(pool.remove(pickedIdx));
        }

        // 3) 按 round 升序展示（正序时间线）
        selected.sort(Comparator.comparing(WorldBook::getRoundNo));

        log.info("[Inquiry-6.3] 加权采样完成 sessionId={} coord=({},{}) perception={} visible={} selected={} secrets={}",
                sessionId, coordX, coordY, perception, visible.size(), selected.size(),
                selected.stream().filter(w -> Boolean.TRUE.equals(w.getIsSecret())).count());
        return selected;
    }

    /**
     * 按地点查询所有事件（不打听，全量返回，用于全知视角）。
     */
    @Transactional(readOnly = true)
    public List<WorldBook> queryByLocation(String sessionId, int coordX, int coordY) {
        return worldBookRepo.findBySessionIdAndCoordXAndCoordYOrderByRoundNoAsc(sessionId, coordX, coordY);
    }

    /**
     * 按角色查询参与过的事件。
     */
    @Transactional(readOnly = true)
    public List<WorldBook> queryByCharacter(String sessionId, String godId) {
        return worldBookRepo.findBySessionIdAndActorContainingOrderByRoundNoDesc(
                sessionId, godId, PageRequest.of(0, INJECT_TOP_N));
    }

    // ==================== 读取：已访问地点 ====================

    /**
     * 获取已访问过的所有地点（供坐标回去 UI 显示）。
     * 返回去重后的地点列表（地点名 + 坐标 + 最近一次访问的回合号）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVisitedLocations(String sessionId) {
        var known=new java.util.HashSet<String>(jdbc.queryForList("SELECT current_scene_id FROM player_profile WHERE active_session_id=?",String.class,sessionId));
        known.addAll(jdbc.queryForList("SELECT DISTINCT scene_id FROM interaction_log WHERE session_id=?",String.class,sessionId));
        known.addAll(jdbc.queryForList("SELECT scene_id FROM role_knowledge WHERE session_id=? AND source_type='VISITED_SCENE' AND god_id=(SELECT player_god_id FROM game_session WHERE session_id=?)",String.class,sessionId,sessionId));
        return sceneRepo.findBySessionId(sessionId).stream().filter(s->known.contains(s.getSceneId())).map(s -> {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("sceneId",s.getSceneId());m.put("locationName",s.getLocationName());
            m.put("coordX",s.getCoordX());m.put("coordY",s.getCoordY());
            return m;
        }).toList();
    }

    /**
     * 获取下一个回合号（本 session 最大 round + 1）。
     */
    @Transactional(readOnly = true)
    public int nextRoundNo(String sessionId) {
        return stateService.nextRound(sessionId);
    }
}
