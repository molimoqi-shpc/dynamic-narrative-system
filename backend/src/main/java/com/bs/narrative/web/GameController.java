package com.bs.narrative.web;

import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.service.CharacterCreationService;
import com.bs.narrative.service.GameActionService;
import com.bs.narrative.service.GameSessionService;
import com.bs.narrative.service.InquiryService;
import com.bs.narrative.repository.NpcDecisionLogRepository;
import com.bs.narrative.service.PlayerMemoryService;
import com.bs.narrative.service.PlayerObjectiveService;
import com.bs.narrative.service.SceneGenerationService;
import com.bs.narrative.service.WorldBookService;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 动态神话互动叙事系统：角色创建 + 场景移动 + 玩家动作 HTTP 入口。
 *
 * 端点：
 *   POST /api/game/start           开始新游戏 → 返回3个问题
 *   POST /api/game/answer           提交3问题答案 → 返回性格标签+天赋列表
 *   POST /api/game/talent/select    选择天赋 → 返回出生故事+初始场景
 *   GET  /api/game/scene           查玩家当前场景
 *   POST /api/game/explore          玩家自由文本驱动移动 → LLM动态生成新场景
 *   POST /api/game/move             （旧）预设出口移动
 *   POST /api/game/move-coord       按坐标回去
 *   POST /api/game/action           玩家对抗动作
 *   GET  /api/game/world-book       查世界书（已访问地点列表）
 */
@RestController
@RequestMapping("/api/game")
@CrossOrigin
public class GameController {

    private final GameActionService gameService;
    private final com.bs.narrative.service.CharacterPanelService characterPanel;
    private final CharacterCreationService creationService;
    private final WorldBookService worldBookService;
    private final SceneGenerationService sceneGenService;
    private final InquiryService inquiryService;
    private final GameSessionService sessionService;
    private final PlayerObjectiveService objectiveService;
    private final PlayerMemoryService memoryService;
    private final NpcDecisionLogRepository npcDecisionRepo;
    private final com.bs.narrative.service.SaveLoadService saveLoadService;
    private final com.bs.narrative.service.SessionStateService stateService;

    public GameController(GameActionService gameService,
                          CharacterCreationService creationService,
                          WorldBookService worldBookService,
                          SceneGenerationService sceneGenService,
                          InquiryService inquiryService,
                          GameSessionService sessionService,
                          PlayerObjectiveService objectiveService,
                          PlayerMemoryService memoryService,
                          NpcDecisionLogRepository npcDecisionRepo,
                          com.bs.narrative.service.SaveLoadService saveLoadService,
                          com.bs.narrative.service.SessionStateService stateService,
                          com.bs.narrative.service.CharacterPanelService characterPanel) {
        this.characterPanel=characterPanel;
        this.gameService = gameService;
        this.creationService = creationService;
        this.worldBookService = worldBookService;
        this.sceneGenService = sceneGenService;
        this.inquiryService = inquiryService;
        this.sessionService = sessionService;
        this.objectiveService = objectiveService;
        this.memoryService = memoryService;
        this.npcDecisionRepo = npcDecisionRepo;
        this.saveLoadService = saveLoadService;
        this.stateService = stateService;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.bs.narrative.service.GameTranscriptService transcript;
    @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.service.ConversationOperationService operations;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.service.LegacyObjectiveRepairService legacyObjectives;
    @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.service.PerspectiveService perspectives;
    @GetMapping("/perspective") public Map<String,Object> perspective(@RequestParam String sessionId,@RequestParam String playerId,@RequestParam(required=false) String roleId,@RequestParam(required=false) Long beforeMemory){return perspectives.read(sessionId,playerId,roleId,beforeMemory);}

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/view-mode") public Map<String,Object> viewMode(@RequestBody Map<String,String> req){
        String mode=req.get("viewMode");if(!java.util.Set.of("DREAMER","OMNISCIENT").contains(java.util.Objects.toString(mode,"")))throw new BusinessException(ErrorCode.PARAM_INVALID,"视角参数无效");
        stateService.lockForOperation(req.get("sessionId"),req.get("playerId"));
        jdbc.update("UPDATE game_session SET view_mode=? WHERE session_id=?",mode,req.get("sessionId"));
        jdbc.update("UPDATE player_profile SET view_mode=? WHERE player_id=?",mode,req.get("playerId"));return Map.of("viewMode",mode);
    }
    @GetMapping("/transcript") public Map<String,Object> transcript(@RequestParam String sessionId,
            @RequestParam(required=false) @Min(1) Long beforeId,jakarta.servlet.http.HttpServletRequest request){
        String owner=(String)request.getAttribute("authenticatedPlayerId");
        return transcript.read(sessionId,owner,beforeId);
    }

    // ==================== V2 角色创建流程 ====================

    /** 开始新游戏：创建会话 + 返回3个问题。 */
    @PostMapping("/start")
    public Map<String, Object> startGame(@Valid @RequestBody StartGameRequest req) {
        return creationService.startGame(
                req.playerId() == null ? "p_demo" : req.playerId(),
                req.viewMode() == null ? "DREAMER" : req.viewMode());
    }

    /** 提交3问题答案 → LLM 生成性格标签+天赋。 */
    @PostMapping("/world")
    public Map<String,Object> generateWorld(@Valid @RequestBody StartGameWorldRequest req) {
        return creationService.generateWorld(req.sessionId(),req.playerId(),req.worldTheme());
    }
    public record StartGameWorldRequest(@jakarta.validation.constraints.NotBlank String sessionId,
                                       @jakarta.validation.constraints.NotBlank String playerId,
                                       @Pattern(regexp="WESTERN|EASTERN|MODERN") String worldTheme) {}

    @PostMapping("/creation/abandon")
    public Map<String,Object> abandonCreation(@Valid @RequestBody StartGameWorldRequest req) {
        return creationService.abandonEmptyDraft(req.sessionId(),req.playerId());
    }

    @PostMapping("/answer")
    public Map<String, Object> submitAnswers(@Valid @RequestBody AnswerRequest req) {
        return creationService.submitAnswers(
                req.sessionId(),
                req.playerId() == null ? "p_demo" : req.playerId(),
                req.answer1(), req.answer2(), req.answer3());
    }

    /** 选择天赋 → LLM 生成出生故事 + 创建初始场景 + 游戏开始。 */
    @PostMapping("/talent/select")
    public Map<String, Object> selectTalent(@Valid @RequestBody SelectTalentRequest req) {
        return creationService.selectTalent(
                req.sessionId(),
                req.playerId() == null ? "p_demo" : req.playerId(),
                req.talentId());
    }

    // ==================== 场景与动作 ====================

    /** 查玩家当前场景。 */
    @GetMapping("/character")
    public Map<String,Object> character(@RequestParam String sessionId,@RequestParam String playerId) {
        return characterPanel.describe(sessionId,playerId);
    }

    /** 查玩家当前场景。 */
    @GetMapping("/scene")
    public Map<String, Object> describeScene(
            @RequestParam(defaultValue = "p_demo") String playerId,
            @RequestParam(defaultValue = "sess_demo_001") String sessionId) {
        return gameService.describeScene(playerId, sessionId);
    }

    /** V2 玩家自由文本驱动移动 → LLM 动态生成新场景。 */
    @PostMapping("/explore")
    public Map<String, Object> explore(@Valid @RequestBody ExploreRequest req,@RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        return submitCompatible(req.sessionId(),req.playerId(),req.playerText(),null,operationId);
    }

    /**
     * 【优化指导 6.2】查当前场景的所有出口（拓扑图边）。
     * 前端出口列表用，玩家可点击已探索的方向快速移动。
     */
    @GetMapping("/exits")
    public Map<String, Object> listExits(
            @RequestParam(defaultValue = "sess_demo_001") String sessionId,
            @RequestParam String sceneId) {
        List<Map<String, Object>> exits = sceneGenService.listExits(sessionId, sceneId);
        return Map.of(
                "sessionId", sessionId,
                "sceneId", sceneId,
                "exits", exits,
                "totalExits", exits.size()
        );
    }

    /** （旧）预设出口移动。 */
    @PostMapping("/move")
    public Map<String,Object> moveScene(@Valid @RequestBody MoveRequest req,@RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        return submitCompatible(req.sessionId(),req.playerId(),"沿"+req.exitName()+"前行。",null,operationId);
    }
    /** Return means an attempted journey through existing routes, never teleportation. */
    @PostMapping("/move-coord")
    public Map<String,Object> moveByCoord(@Valid @RequestBody MoveCoordRequest req,@RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        var destination=knownLocation(req.sessionId(),null,req.coordX(),req.coordY());
        return submitCompatible(req.sessionId(),req.playerId(),"沿已知路线回到"+destination.get("locationName")+"。",null,operationId);
    }
    private Map<String,Object> knownLocation(String sid,String id,Integer x,Integer y){
        return worldBookService.getVisitedLocations(sid).stream().filter(loc->id!=null?id.equals(loc.get("sceneId")):java.util.Objects.equals(x,loc.get("coordX")) && java.util.Objects.equals(y,loc.get("coordY"))).findFirst()
            .orElseThrow(()->new BusinessException(ErrorCode.PARAM_INVALID,"地点尚未亲历，请使用已知路线探索"));
    }
    private Map<String,Object> submitCompatible(String sid,String pid,String input,ActionRequest.DialogueInput dialogue,String operationId){
        String sceneId=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=? AND active_session_id=?",String.class,pid,sid);
        return operations.submit(new com.bs.narrative.service.ConversationOperationService.Request(operationId==null?java.util.UUID.randomUUID().toString():operationId,sid,pid,sceneId,input,dialogue,null,null));
    }

    /** 玩家对抗动作。 */
    @PostMapping("/action")
    public Map<String,Object> processAction(@Valid @RequestBody ActionRequest req,
                                        @RequestParam(defaultValue = "sess_demo_001") String sessionId,
                                        @RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        if (req.structuredIntent() != null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "行动意图必须由服务器解析，请只提交玩家文本");
        }
        return operations.create(new com.bs.narrative.service.ConversationOperationService.Request(
            operationId==null?java.util.UUID.randomUUID().toString():operationId,sessionId,req.playerId(),req.sceneId(),req.playerText(),req.dialogue(),null,null));
    }

    // ==================== 游戏生命周期 ====================

    /** 检查玩家是否死亡。 */
    @PostMapping("/death-check")
    public Map<String, Object> checkDeath(@Valid @RequestBody DeathCheckRequest req) {
        boolean dead = sessionService.checkDeath(req.sessionId(), req.playerId());
        return Map.of("sessionId", req.sessionId(), "isDead", dead);
    }

    /** 处理玩家死亡 → 生成总结。 */
    @PostMapping("/death")
    public Map<String, Object> processDeath(@Valid @RequestBody DeathRequest req) {
        return sessionService.processDeath(req.sessionId(), req.playerId());
    }

    /** 重新开始（可选继承世界书）。 */
    @PostMapping("/restart")
    public Map<String, Object> restart(@Valid @RequestBody RestartRequest req) {
        return sessionService.restart(
                req.playerId() == null ? "p_demo" : req.playerId(),
                req.viewMode(),
                req.inheritWorld() != null && req.inheritWorld(),
                req.prevSessionId());
    }

    /** 查会话状态。 */
    @GetMapping("/session-status")
    public Map<String, Object> getSessionStatus(@RequestParam String sessionId) {
        return sessionService.getSessionStatus(sessionId);
    }

    /** 查玩家所有会话（存档列表）。 */
    @GetMapping("/sessions")
    public Map<String, Object> getPlayerSessions(@RequestParam String playerId) {
        return Map.of("playerId", playerId, "sessions", sessionService.getPlayerSessions(playerId));
    }

    // ==================== 事件溯源存档（Phase 6.4） ====================

    /**
     * 【优化指导 6.4】查某会话所有可回溯的存档点（按回合数）。
     * 前端存档列表 UI 用，每个存档点 = 1 个 round_no。
     */
    @GetMapping("/save-points")
    public Map<String, Object> listSavePoints(@RequestParam String sessionId) {
        var saves = saveLoadService.listSavePoints(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "currentRound", saveLoadService.getCurrentRound(sessionId),
                "savePoints", saves,
                "totalSaves", saves.size()
        );
    }

    /**
     * 【优化指导 6.4】读档到第 N 回合（事件溯源回溯）。
     * 删除 session_id=xxx 且 round_no > N 的所有记录，恢复玩家位置。
     */
    @PostMapping("/load-to-round")
    public Map<String, Object> loadToRound(@Valid @RequestBody LoadToRoundRequest req) {
        return saveLoadService.loadToRound(req.sessionId(), req.targetRound());
    }

    /** 读档后角色状态快照（论文截图用）。 */
    @GetMapping("/snapshot")
    public Map<String, Object> getSnapshot(
            @RequestParam String sessionId,
            @RequestParam String playerId) {
        return saveLoadService.getSnapshotAfterRewind(sessionId, playerId);
    }

    /** 6.4 读档请求体。 */
    public record LoadToRoundRequest(
            String sessionId,
            @NotNull @Min(0) Integer targetRound
    ) {}

    // ==================== 打听机制 ====================

    /** V2 玩家在当前地点打听 → 随机获取部分世界书信息。 */
    @PostMapping("/inquire")
    public Map<String,Object> inquire(@Valid @RequestBody InquireRequest req,@RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        if(req.targetGodId()==null || req.targetGodId().isBlank())throw new BusinessException(ErrorCode.PARAM_INVALID,"打听须选择实际在场的信息来源");
        var location=knownLocation(req.sessionId(),null,req.coordX(),req.coordY());String question="你了解"+location.get("locationName")+"最近的情况吗？";
        return submitCompatible(req.sessionId(),req.playerId(),question,new ActionRequest.DialogueInput(req.targetGodId(),question),operationId);
    }

    // ==================== 世界书 ====================

    /** 查世界书：已访问地点列表（供坐标回去 UI）。 */
    @GetMapping("/world-book")
    public Map<String, Object> getVisitedLocations(
            @RequestParam String sessionId) {
        var locations = worldBookService.getVisitedLocations(sessionId);
        return Map.of("sessionId", sessionId, "visitedLocations", locations);
    }

    // ==================== 玩家目标系统（Phase 8） ====================

    @GetMapping("/objectives/legacy-repair")
    public Map<String,Object> legacyObjectivePreview(@RequestParam String sessionId,jakarta.servlet.http.HttpServletRequest request){
        return legacyObjectives.preview(sessionId,(String)request.getAttribute("authenticatedPlayerId"));
    }
    public record LegacyObjectiveRepairRequest(@NotBlank String sessionId,@Min(0) long branchEpoch,@Min(0) long expectedRevision,@NotBlank String previewToken){}
    @PostMapping("/objectives/legacy-repair")
    public Map<String,Object> repairLegacyObjectives(@Valid @RequestBody LegacyObjectiveRepairRequest req,jakarta.servlet.http.HttpServletRequest request){
        return legacyObjectives.apply(req.sessionId(),(String)request.getAttribute("authenticatedPlayerId"),req.branchEpoch(),req.expectedRevision(),req.previewToken());
    }

    /** 查玩家在某 session 的所有目标。 */
    @GetMapping("/objectives")
    public Map<String, Object> listObjectives(
            @RequestParam String playerId,
            @RequestParam String sessionId) {
        var objs = objectiveService.listObjectives(playerId, sessionId);
        return Map.of(
                "sessionId", sessionId,
                "playerId", playerId,
                "objectives", objs.stream().map(o -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("objectiveId", o.getObjectiveId());
                    m.put("title", o.getTitle());
                    m.put("status", o.getStatus());
                    m.put("progress", o.getProgress());
                    m.put("currentStep", o.getCurrentStep());
                    return m;
                }).toList()
        );
    }

    /** 多结局判定。playerDead=true 时走 DEFEAT 分支。 */
    @GetMapping("/ending")
    public Map<String, Object> evaluateEnding(
            @RequestParam String sessionId,
            @RequestParam String playerId,
            @RequestParam(defaultValue = "false") boolean playerDead) {
        return objectiveService.evaluateEnding(sessionId, playerId, sessionService.checkDeath(sessionId,playerId));
    }

    // ==================== 玩家记忆系统（Phase 9） ====================

    /**
     * 查玩家在某 session 的记忆上下文（天眼面板"记忆窗口"用）。
     * 返回 Pass2 Prompt 注入用的格式化记忆段：ROUND Top3 + SCENE + GLOBAL。
     */
    @GetMapping("/memory")
    public Map<String, Object> getPlayerMemory(
            @RequestParam String playerId,
            @RequestParam String sessionId) {
        if(!perspectives.omniscient(sessionId))return Map.of("sessionId",sessionId,"playerId",playerId,"viewMode","DREAMER","memoryContext","");
        String context = memoryService.buildContextForPrompt(playerId, sessionId);
        return Map.of(
                "sessionId", sessionId,
                "playerId", playerId,
                "viewMode", "OMNISCIENT",
                "memoryContext", context
        );
    }

    // ==================== NPC 群戏决策追溯（Phase 10 论文级） ====================

    /**
     * 查某会话所有 NPC 决策日志（按时间倒序，论文截图用）。
     * 每条包含：NPC ID、行为、公开对白、内心、是否说谎。
     */
    @GetMapping("/npc-decisions")
    public Map<String, Object> listNpcDecisions(
            @RequestParam String sessionId,
            @RequestParam(required = false) Long logId,
            @RequestParam(required = false) String godId) {
        if(!"OMNISCIENT".equals(jdbc.queryForObject("SELECT view_mode FROM game_session WHERE session_id=?",String.class,sessionId)))return Map.of("sessionId",sessionId,"totalDecisions",0,"decisions",List.of());
        List<Map<String, Object>> list;
        if (logId != null) {
            // 查某回合所有 NPC 决策（天眼面板"本回合群戏"用）
            list = npcDecisionRepo.findBySessionIdAndLogIdOrderByGodIdAsc(sessionId, logId)
                    .stream().map(this::briefNpcDecision).toList();
        } else if (godId != null) {
            // 查某 NPC 的全部决策轨迹（角色行为分析用）
            list = npcDecisionRepo.findBySessionIdAndGodIdOrderByCreatedAtDesc(sessionId, godId)
                    .stream().map(this::briefNpcDecision).toList();
        } else {
            // 全部决策日志（论文截图用）
            list = npcDecisionRepo.findBySessionIdOrderByCreatedAtDesc(sessionId)
                    .stream().map(this::briefNpcDecision).toList();
        }
        var combined=new java.util.ArrayList<>(list);combined.addAll(operations.backstage(sessionId).stream()
                .filter(d->logId==null || d.get("logId") instanceof Number n && n.longValue()==logId)
                .filter(d->godId==null || godId.equals(d.get("godId"))).toList());
        return Map.of(
                "sessionId", sessionId,
                "totalDecisions", combined.size(),
                "decisions", combined
        );
    }

    /** NPC 决策摘要（前端群戏追溯面板 + 论文截图用）。 */
    private Map<String, Object> briefNpcDecision(com.bs.narrative.domain.NpcDecisionLog d) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("decisionId",    d.getDecisionId());
        m.put("logId",          d.getLogId());
        m.put("godId",          d.getGodId());
        m.put("godName",gameService.characterName(d.getSessionId(),d.getGodId()));
        m.put("sceneId",        d.getSceneId());
        m.put("playerInput",    d.getPlayerInput());
        m.put("behavior",      d.getBehavior());
        m.put("speech",         d.getSpeech());
        m.put("innerThought",   d.getInnerThought());
        m.put("isLying",        d.getIsLying());
        m.put("usedFallback",   d.getUsedFallback());
        m.put("createdAt",      d.getCreatedAt());
        return m;
    }

    // ==================== 请求体定义 ====================

    public record StartGameRequest(String playerId, @Pattern(regexp="DREAMER|OMNISCIENT") String viewMode) {}
    @PostMapping("/return-scene")
    public Map<String,Object> returnScene(@Valid @RequestBody ReturnSceneRequest req,@RequestHeader(value="X-Operation-ID",required=false) String operationId) {
        var destination=knownLocation(req.sessionId(),req.sceneId(),null,null);
        return submitCompatible(req.sessionId(),req.playerId(),"沿已知路线回到"+destination.get("locationName")+"。",null,operationId);
    }
    public record ReturnSceneRequest(String sessionId,String playerId,@NotBlank @Size(max=64) String sceneId) {}
    @PostMapping("/resume")
    public Map<String,Object> resume(@Valid @RequestBody DeathCheckRequest req) {
        return stateService.resume(req.sessionId(),req.playerId());
    }
    @GetMapping("/creation")
    public Map<String,Object> creation(@RequestParam String sessionId,@RequestParam String playerId) {
        return creationService.creationState(sessionId,playerId);
    }
    public record AnswerRequest(String sessionId, String playerId,
                                @NotBlank @Size(max=2000) String answer1, @NotBlank @Size(max=2000) String answer2, @NotBlank @Size(max=2000) String answer3) {}
    public record SelectTalentRequest(String sessionId, String playerId, @NotNull @Positive Long talentId) {}
    public record ExploreRequest(String sessionId, String playerId, @NotBlank @Size(max=4000) String playerText) {}
    public record InquireRequest(String sessionId, String playerId, @NotNull Integer coordX, @NotNull Integer coordY, String targetGodId) {}
    public record DeathCheckRequest(String sessionId, String playerId) {}
    public record DeathRequest(String sessionId, String playerId) {}
    public record RestartRequest(String playerId, @Pattern(regexp="DREAMER|OMNISCIENT") String viewMode, Boolean inheritWorld, String prevSessionId) {}
    public record MoveRequest(String playerId, String sessionId, @NotBlank @Size(max=50) String exitName) {}
    public record MoveCoordRequest(String playerId, String sessionId,
                                   @NotNull Integer coordX, @NotNull Integer coordY) {}
}
