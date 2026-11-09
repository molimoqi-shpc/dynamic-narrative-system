package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * V2 游戏会话生命周期管理：开始→进行→死亡→总结→重启。
 *
 * 核心功能：
 *   1) checkDeath：查询已登记的死亡终局
 *   2) processDeath：读取已登记的死亡总结，不重复生成或改写状态
 *   3) restart：创建新会话（可选继承世界书）
 *   4) getSessionStatus：查会话状态
 */
@Service
public class GameSessionService {
    @org.springframework.beans.factory.annotation.Autowired private StoryEndingService lifeEndings;
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;

    private static final Logger log = LoggerFactory.getLogger(GameSessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GameSessionRepository sessionRepo;
    private final GodProfileRepository godRepo;
    private final PlayerProfileRepository playerRepo;
    private final CharacterCreationRepository creationRepo;
    private final TalentRepository talentRepo;
    private final WorldBookService worldBookService;
    private final LLMProvider llmProvider;
    private final PlayerObjectiveService objectiveService;
    private final CharacterCreationService creationService;
    private final SessionStateService stateService;

    public GameSessionService(GameSessionRepository sessionRepo,
                              GodProfileRepository godRepo,
                              PlayerProfileRepository playerRepo,
                              CharacterCreationRepository creationRepo,
                              TalentRepository talentRepo,
                              WorldBookService worldBookService,
                              LLMProvider llmProvider,
                              PlayerObjectiveService objectiveService,
                              CharacterCreationService creationService, SessionStateService stateService) {
        this.sessionRepo = sessionRepo;
        this.godRepo = godRepo;
        this.playerRepo = playerRepo;
        this.creationRepo = creationRepo;
        this.talentRepo = talentRepo;
        this.worldBookService = worldBookService;
        this.llmProvider = llmProvider;
        this.objectiveService = objectiveService;
        this.creationService=creationService; this.stateService=stateService;
    }

    /** Only an explicitly registered terminal state is evidence of death; six stats are not health. */
    @Transactional(readOnly = true)
    public boolean checkDeath(String sessionId,String playerId){
        var session=sessionRepo.findById(sessionId).filter(s->playerId.equals(s.getPlayerId())).orElse(null);
        return session!=null && ("DEAD".equals(session.getStatus()) || "SUMMARIZED".equals(session.getStatus()) && session.getDeathSummary()!=null);
    }

    /**
     * 返回已登记死亡终局；查询和网络重试不产生新事件。
     *
     * @return {deathSummary, finalEvaluation}
     */
    @Transactional(readOnly = true)
    public Map<String,Object> processDeath(String sessionId,String playerId){
        if(!checkDeath(sessionId,playerId))throw new BusinessException(ErrorCode.DEATH_NOT_REGISTERED,"未登记角色死亡事实，当前不能读取死亡结局");
        var session=sessionRepo.findById(sessionId).orElseThrow();
        var result=new LinkedHashMap<String,Object>();result.put("deathSummary",Objects.toString(session.getDeathSummary(),""));
        result.put("finalEvaluation",lifeEndings.read(sessionId).getOrDefault("finalEvaluation",""));result.put("sessionId",sessionId);result.put("options",List.of("restart","exit","switch_mode"));
        var ending=objectiveService.evaluateEnding(sessionId,playerId,true);
        result.put("ending",ending.get("ending"));result.put("endingDescription",ending.get("description"));
        result.put("endingStats",Map.of("totalObjectives",ending.get("totalObjectives"),"completedObjectives",ending.get("completedObjectives"),"failedObjectives",ending.get("failedObjectives")));return result;
    }

    /**
     * 重新开始：创建新会话，可选继承世界书。
     *
     * @return {newSessionId, inheritedWorld}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> restart(String playerId, String viewMode,
                                        boolean inheritWorld, String prevSessionId) {
        Map<String,Object> creation=creationService.startGame(playerId,viewMode);
        String newSessionId=(String)creation.get("sessionId");
        String vm = viewMode == null ? "DREAMER" : viewMode;

        GameSession newSession = sessionRepo.findById(newSessionId).orElseThrow();
        newSession.setPlayerId(playerId);
        newSession.setViewMode(vm);
        newSession.setStatus("CREATING");
        newSession.setInheritWorld(inheritWorld);
        newSession.setPrevSessionId(prevSessionId);
        sessionRepo.save(newSession);

        log.info("[GameSession] 新会话创建 sessionId={} player={} viewMode={} inherit={}",
                newSessionId, playerId, vm, inheritWorld);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newSessionId", newSessionId);
        result.put("viewMode", vm);
        result.put("inheritedWorld", inheritWorld);
        return result;
    }

    /**
     * 查会话状态。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSessionStatus(String sessionId) {
        GameSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) {
            return Map.of("error", "会话不存在：" + sessionId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("status", session.getStatus());
        result.put("viewMode", session.getViewMode());
        result.putAll(lifeEndings.read(sessionId));
        if (session.getDeathSummary() != null) {
            result.put("deathSummary", session.getDeathSummary());
        }
        return result;
    }

    /**
     * 查玩家所有会话（供读取存档 UI）。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPlayerSessions(String playerId) {
        return sessionRepo.findByPlayerIdOrderByCreatedAtDesc(playerId).stream()
                .map(s -> {
                    var world=worldSettings.find(s.getSessionId());
                    // An opening request is not a save. Preserve meaningful legacy
                    // creations, but never list a failed/untouched blank request.
                    if("CREATING".equals(s.getStatus()) && world.isEmpty()
                            && creationRepo.findBySessionIdAndPlayerId(s.getSessionId(),playerId)
                            .map(c->!List.of("TALENT_GENERATED","COMPLETED").contains(c.getStatus())).orElse(true))return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", s.getSessionId());
                    m.put("status", s.getStatus());
                    m.put("viewMode", s.getViewMode());
                    m.put("createdAt", s.getCreatedAt());
                    m.put("worldTitle", world.path("title").asText(""));
                    m.put("showcase",world.path("showcase").asBoolean(false));
                    m.put("chapterTitle",world.path("showcaseTitle").asText(""));
                    return m;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Map<String,Object> s)->Boolean.TRUE.equals(s.get("showcase"))).reversed())
                .toList();
    }
}
