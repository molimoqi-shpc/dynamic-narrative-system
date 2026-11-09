package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.domain.PlayerProfile;
import com.bs.narrative.domain.SceneConnection;
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
 * V2 动态场景生成：玩家自由文本驱动移动 → LLM 生成新场景。
 *
 * 【优化指导 6.2】从 (x,y) 直角坐标改为节点(Scene)+边(Exit)的拓扑图结构：
 *   1) 玩家说"我往北走" → LLM 输出 direction="NORTH"
 *   2) 查 scene_connection 是否有 NORTH 方向的边？
 *      - 有：跳转到目标场景（已访问地点回溯）
 *      - 无：调 LLM 生成新场景信息，创建新 GameScene + 建立双向边
 *   3) 更新玩家位置
 *   4) 写世界书 MOVE 事件
 *
 * 论文表述："基于动态图遍历的场景探索机制"
 *
 * 兼容：coord_x/coord_y 字段保留但不再作为主探索键，仅供旧数据展示。
 */
@Service
public class SceneGenerationService {
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;

    private static final Logger log = LoggerFactory.getLogger(SceneGenerationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 方向枚举反向映射（建立双向边用）。 */
    private static final Map<String, String> OPPOSITE_DIR = Map.of(
            "NORTH", "SOUTH", "SOUTH", "NORTH",
            "EAST", "WEST", "WEST", "EAST",
            "UP", "DOWN", "DOWN", "UP",
            "OTHER", "OTHER"
    );

    private final LLMProvider llmProvider;
    private final SessionStateService stateService;
    private final SessionNpcService npcProfiles;
    private final WorldTurnService worldTurns;
    private final GameSceneRepository sceneRepo;
    private final PlayerProfileRepository playerRepo;
    private final WorldBookService worldBookService;
    private final Pass2Service pass2Service;
    private final SceneConnectionRepository connRepo;

    public SceneGenerationService(LLMProvider llmProvider,
                                   GameSceneRepository sceneRepo,
                                   PlayerProfileRepository playerRepo,
                                   WorldBookService worldBookService,
                                   Pass2Service pass2Service,
                                   SceneConnectionRepository connRepo,
                                   SessionStateService stateService, SessionNpcService npcProfiles, WorldTurnService worldTurns) {
        this.worldTurns=worldTurns;
        this.llmProvider = llmProvider;
        this.stateService=stateService; this.npcProfiles=npcProfiles;
        this.sceneRepo = sceneRepo;
        this.playerRepo = playerRepo;
        this.worldBookService = worldBookService;
        this.pass2Service = pass2Service;
        this.connRepo = connRepo;
    }

    /**
     * 玩家自由文本驱动的动态移动（拓扑图版）。
     *
     * @param sessionId  会话ID
     * @param playerId   玩家ID
     * @param playerText 玩家移动意图文本（如"我往北走"、"穿过芦苇荡"）
     * @return {sceneId, locationName, direction, exits, sceneDescription, narrative, visitedBefore}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> explore(String sessionId, String playerId, String playerText) {
        stateService.begin(sessionId,playerId);
        // 1) 查玩家当前位置
        PlayerProfile player = playerRepo.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("玩家不存在：" + playerId));
        GameScene currentScene = sceneRepo.findBySessionIdAndSceneId(sessionId, player.getCurrentSceneId())
                .orElseThrow(() -> new IllegalStateException("当前场景不存在"));
        String currentLocationName = currentScene.getLocationName();
        String currentSceneId = currentScene.getSceneId();

        // 2) 调 LLM 解析玩家移动意图 → direction + exit_name + 新场景信息
        //    （仅在无已知出口时才需要生成新场景信息）
        String worldBookContext = worldBookService.buildContextForPrompt(sessionId);

        LLMResponse llmResp;
        boolean usedFallback = false;
        try {
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content", SceneGenerationPromptTemplate.systemPrompt()+worldSettings.prompt(sessionId)),
                    Map.of("role", "user",   "content", SceneGenerationPromptTemplate.userPrompt(
                            playerText, currentLocationName, worldBookContext))
            );
            llmResp = llmProvider.chatForJson(msgs);
        } catch (LLMApiException e) {
            throw new BusinessException(ErrorCode.LLM_API_FAILED,
                    "场景生成暂时不可用，位置和存档未改变，请重试或选择已有出口", e.isRetriable(), e);
        }

        String direction, exitName, locationName, sceneDescription;
        List<String> presentNpcs;
        try {
            JsonNode root = ModelOutput.object(llmResp.parsedJson() != null ? llmResp.parsedJson() : llmResp.content());
            direction = ModelOutput.text(root,"direction","",16).toUpperCase(Locale.ROOT);
            if (!OPPOSITE_DIR.containsKey(direction)) throw new IllegalArgumentException("移动方向无效");
            exitName = ModelOutput.text(root,"exit_name","继续前行",50);
            locationName = ModelOutput.text(root,"location_name","荒径迷途",50);
            sceneDescription = ModelOutput.text(root,"scene_description","荒径蜿蜒，远处雾气弥漫。",8000);
            presentNpcs = new ArrayList<>(ModelOutput.names(root.path("present_npcs"),8,50));
            presentNpcs.addAll(SceneCastService.groundedNames(root.path("scene_cast"),sceneDescription));
            presentNpcs=presentNpcs.stream().distinct().limit(8).toList();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LLM_PARSE_FAILED,
                    "生成的场景格式无效，位置和存档未改变，请重试",true,e);
        }

        // 4) 拓扑图遍历：查当前场景是否有该方向的出口
        Optional<SceneConnection> existingConn = connRepo
                .findBySessionIdAndFromSceneIdAndDirection(sessionId, currentSceneId, direction);

        GameScene targetScene;
        boolean visitedBefore = false;

        if (existingConn.isPresent()) {
            // 4a) 有出口：跳转到目标场景（已访问地点回溯，零 LLM 调用）
            SceneConnection conn = existingConn.get();
            stateService.checkGate(sessionId,playerId,conn);
            Optional<GameScene> targetOpt = sceneRepo.findBySessionIdAndSceneId(sessionId, conn.getToSceneId());
            if (targetOpt.isPresent()) {
                targetScene = targetOpt.get();
                visitedBefore = true;
                log.info("[SceneGen] 沿 {} 方向跳转已有出口 sessionId={} from={} to={} dir={}",
                        sessionId, currentSceneId, targetScene.getSceneId(), direction);
            } else {
                throw new BusinessException(ErrorCode.SCENE_NOT_FOUND,
                        "出口目标场景不存在，未移动，请检查该存档的地图数据");
            }
        } else {
            // 4b) 无出口：创建新场景 + 建立双向边
            targetScene = createNewScene(sessionId, direction, locationName,
                    sceneDescription, presentNpcs, usedFallback);
            createBidirectionalEdge(sessionId, currentSceneId, targetScene.getSceneId(),
                    direction, exitName, null);
            log.info("[SceneGen] 新场景创建 sessionId={} sceneId={} location={} dir={} fallback={}",
                    sessionId, targetScene.getSceneId(), locationName, direction, usedFallback);
        }

        // 5) 更新玩家位置
        player.setCurrentSceneId(targetScene.getSceneId());
        playerRepo.save(player);

        var worldTurn=worldTurns.advance(sessionId,stateService.nextRound(sessionId),targetScene.getSceneId(),player.getGodId());
        // 6) 写世界书 MOVE 事件
        int roundNo = worldBookService.nextRoundNo(sessionId);
        worldBookService.addEvent(
                sessionId, roundNo, worldTurns.time(sessionId),
                targetScene.getCoordX() == null ? 0 : targetScene.getCoordX(),
                targetScene.getCoordY() == null ? 0 : targetScene.getCoordY(),
                targetScene.getLocationName(),
                "MOVE",
                List.of(player.getGodId()),
                visitedBefore
                        ? "玩家沿" + direction + "方向回到" + targetScene.getLocationName() + "（已访问过的地点）"
                        : "玩家从" + currentLocationName + "沿" + direction + "方向抵达" + targetScene.getLocationName(),
                "方向：" + direction + "（" + exitName + "）",
                visitedBefore ? 3 : 5);

        // 7) 调 Pass2 生成到达叙事
        List<String> facts = new ArrayList<>();
        if (visitedBefore) {
            facts.add("玩家沿" + direction + "方向回到曾到访的" + targetScene.getLocationName());
        } else {
            facts.add("玩家从" + currentLocationName + "沿" + direction + "方向抵达" + targetScene.getLocationName());
        }

        facts.addAll(worldTurn.events().stream().map(WorldTurnService.VisibleEvent::text).toList());
        Pass2Service.Pass2Result p2 = pass2Service.generateEnsembleNarrativeWithMemory(
                "NO_OP",
                visitedBefore ? "故地重游" : "抵达新地点",
                playerText,
                targetScene.getPresentGods() == null ? List.of() : targetScene.getPresentGods(),
                facts,
                targetScene.getSceneSummary()+"\n实际在场角色索引："+npcProfiles.publicIndex(sessionId,targetScene.getPresentGods()==null?List.of():targetScene.getPresentGods()),"");

        // 8) 查新场景的出口列表（供前端展示）
        List<Map<String, Object>> exits = listExits(sessionId, targetScene.getSceneId());

        // 9) 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sceneId", targetScene.getSceneId());
        result.put("locationName", targetScene.getLocationName());
        result.put("worldTick",worldTurn.tick());result.put("storyTime",worldTurns.time(sessionId));
        result.put("direction", direction);
        result.put("exitName", exitName);
        result.put("coordX", targetScene.getCoordX());
        result.put("coordY", targetScene.getCoordY());
        result.put("sceneDescription", targetScene.getSceneSummary());
        result.put("presentNpcs", targetScene.getPresentGods() == null ? List.of() : targetScene.getPresentGods());
        result.put("visitedBefore", visitedBefore);
        result.put("exits", exits);
        result.put("narrative", p2.narrativeLines());
        result.put("facts", facts);
        result.put("token", Map.of(
                "input", p2.llmResponse() == null ? 0 : p2.llmResponse().tokenInput(),
                "output", p2.llmResponse() == null ? 0 : p2.llmResponse().tokenOutput(),
                "total", p2.llmResponse() == null ? 0 : p2.llmResponse().tokenTotal(),
                "costCny", p2.llmResponse() == null ? 0.0 : p2.llmResponse().costCny()));
        stateService.complete(sessionId,playerText);
        return result;
    }

    /** 查某场景的所有出口（前端出口列表用）。 */
    public List<Map<String, Object>> listExits(String sessionId, String sceneId) {
        List<SceneConnection> conns = connRepo
                .findBySessionIdAndFromSceneIdOrderByDirectionAsc(sessionId, sceneId);
        List<Map<String, Object>> exits = new ArrayList<>();
        for (SceneConnection c : conns) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("direction", c.getDirection());
            e.put("exitName", c.getExitName());
            e.put("toSceneId", c.getToSceneId());
            e.put("note", c.getNote());
            exits.add(e);
        }
        return exits;
    }

    /** 创建新场景并保存。 */
    private GameScene createNewScene(String sessionId, String direction,
                                      String locationName, String sceneDescription,
                                      List<String> presentNpcs, boolean usedFallback) {
        String sceneId = "scene_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        GameScene scene = new GameScene();
        scene.setSceneId(sceneId);
        scene.setSessionId(sessionId);
        scene.setLocationName(locationName);
        // coord_x/coord_y 保留但不再作为主键，仅供旧数据展示
        // Legacy coordinate APIs remain unambiguous while scene IDs are the primary key.
        int coordinate=sceneRepo.findBySessionId(sessionId).stream().mapToInt(s->s.getCoordX()==null?0:s.getCoordX()).max().orElse(0)+1;
        scene.setCoordX(coordinate); scene.setCoordY(0);
        scene.setPresentGods(new ArrayList<>(npcProfiles.resolve(sessionId,presentNpcs)));
        scene.setSceneSummary(sceneDescription);
        scene.setLogCount(0);
        sceneRepo.save(scene);
        return scene;
    }

    /**
     * 建立双向边（优化指导 6.2）：
     *   A 方向通 B / B 反方向通 A
     * 若边已存在则跳过（幂等）。
     */
    private void createBidirectionalEdge(String sessionId, String fromSceneId, String toSceneId,
                                          String direction, String exitName, String note) {
        String reverseDir = OPPOSITE_DIR.getOrDefault(direction, "OTHER");
        String reverseExitName = reverseDir.equals("OTHER") ? "原路返回" : reverseExitName(reverseDir);

        // 正向边 A→B
        if (connRepo.findBySessionIdAndFromSceneIdAndDirection(sessionId, fromSceneId, direction).isEmpty()) {
            SceneConnection forward = new SceneConnection();
            forward.setSessionId(sessionId);
            forward.setFromSceneId(fromSceneId);
            forward.setToSceneId(toSceneId);
            forward.setDirection(direction);
            forward.setExitName(exitName);
            forward.setNote(note);
            connRepo.save(forward);
        }

        // 反向边 B→A
        if (connRepo.findBySessionIdAndFromSceneIdAndDirection(sessionId, toSceneId, reverseDir).isEmpty()) {
            SceneConnection backward = new SceneConnection();
            backward.setSessionId(sessionId);
            backward.setFromSceneId(toSceneId);
            backward.setToSceneId(fromSceneId);
            backward.setDirection(reverseDir);
            backward.setExitName(reverseExitName);
            backward.setNote("原路返回");
            connRepo.save(backward);
        }
    }

    /** 反向出口名（古风）。 */
    private String reverseExitName(String reverseDir) {
        return switch (reverseDir) {
            case "NORTH" -> "向北折返";
            case "SOUTH" -> "向南折返";
            case "EAST" -> "向东折返";
            case "WEST" -> "向西折返";
            case "UP" -> "拾级而上";
            case "DOWN" -> "顺势而下";
            default -> "原路返回";
        };
    }
}

