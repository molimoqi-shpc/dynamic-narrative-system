package com.bs.narrative.service;

import com.bs.narrative.repository.*;
import com.bs.narrative.engine.*;
import com.bs.narrative.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Read-only profile. Never promotes the player or invents items or friendships. */
@Service
public class CharacterPanelService {
    @org.springframework.beans.factory.annotation.Autowired private VitalStateService vitality;
    @org.springframework.beans.factory.annotation.Autowired private ItemStateService items;
    @org.springframework.beans.factory.annotation.Autowired private PlayerJournalService journal;
    private final GameSessionRepository sessions;
    private final PlayerProfileRepository players;
    private final GodProfileRepository gods;
    private final GodRelationshipRepository relationships;
    private final GameSceneRepository scenes;
    private final CharacterCreationRepository creations;
    private final TalentRepository talents;
    public CharacterPanelService(GameSessionRepository sessions,PlayerProfileRepository players,
            GodProfileRepository gods,GodRelationshipRepository relationships,GameSceneRepository scenes,
            CharacterCreationRepository creations,TalentRepository talents) {
        this.sessions=sessions;this.players=players;this.gods=gods;this.relationships=relationships;
        this.scenes=scenes;this.creations=creations;this.talents=talents;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> describe(String sid,String pid) {
        var session=sessions.findById(sid).orElseThrow(()->new BusinessException(ErrorCode.PARAM_INVALID,"会话不存在"));
        if(!pid.equals(session.getPlayerId())) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,"无权查看该人物");
        var player=players.findById(pid).orElseThrow(()->new BusinessException(ErrorCode.PLAYER_NOT_FOUND,"玩家不存在"));
        String role=session.getPlayerGodId();
        if(role==null && gods.findById(player.getGodId()).filter(g->sid.equals(g.getSessionId())).isPresent()) role=player.getGodId();
        if(role==null) throw new BusinessException(ErrorCode.DATA_CONFLICT,"请先恢复该存档，再查看人物");
        var god=gods.findById(role).orElseThrow(()->new BusinessException(ErrorCode.GOD_NOT_FOUND,"角色尚未诞生"));
        if(!Objects.equals(sid,god.getSessionId())) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,"角色与周目不一致");
        var actual=StatsCalculator.calcActualStats(god);
        Map<String,Object> result=new LinkedHashMap<>();
        String playerName=journal.name(sid,role,god.getGodName());
        result.put("sessionId",sid);result.put("godId",role);result.put("name",playerName);
        result.put("emotion",god.getGodEmotion());result.put("status",session.getStatus());
        result.put("health",vitality.health(sid,role));
        result.put("round",Objects.requireNonNullElse(session.getCurrentRound(),0));
        result.put("baseStats",actual.basesAsMap());result.put("actualStats",actual.actualsAsMap());
        result.put("formula",StatFormulaBook.describe(god));
        result.put("inventory",god.getInventory()==null?List.of():List.copyOf(god.getInventory()));
        var inventory=items.describeOwned(sid,god);result.put("inventoryDetails",inventory);
        result.put("importantItems",journal.importantItems(sid,role,inventory));
        String state=Objects.requireNonNullElse(god.getGodStatus(),"UNKNOWN");
        result.put("godStatus",state);
        result.put("divinity",switch(state) {
            case "MORTAL" -> "普通人 · 身份以故事记录为准";
            case "DEITY" -> "神明 · 神职尚未命名";
            case "RAIN" -> "司雨神职";
            case "MUSEUM" -> "博物神职";
            case "FARM" -> "农事神职";
            case "ALIVE" -> "生命存续 · 身份尚未明确";
            default -> "身份标记 · "+state;
        });
        String currentScene=Objects.equals(sid,player.getActiveSessionId())?player.getCurrentSceneId():session.getCurrentSceneId();
        result.put("location",currentScene==null?"尚未入世":scenes.findBySessionIdAndSceneId(sid,currentScene).map(s->s.getLocationName()).orElse("地点未记录"));
        List<Map<String,Object>> friends=new ArrayList<>(),enemies=new ArrayList<>(),known=new ArrayList<>();
        for(var connection:journal.relationships(sid,role,pid)) {
            String kind=Objects.toString(connection.get("kind"),"ACQUAINTANCE");
            if(Set.of("ALLY","FRIEND").contains(kind))friends.add(connection);else if(Set.of("ENEMY","RIVAL").contains(kind))enemies.add(connection);else known.add(connection);
        }
        result.put("friends",friends);result.put("enemies",enemies);result.put("acquaintances",known);
        result.put("relationshipBasis","记录实际相识、交往与已成立的关系。未明确立场者列入相识；戒备不等于已经敌对，不展示对方未公开的内心。");
        creations.findBySessionIdAndPlayerId(sid,pid).ifPresent(creation->{
            result.put("personality",creation.getPersonalityTags()==null?List.of():creation.getPersonalityTags());
            result.put("birthStory",Objects.requireNonNullElse(creation.getBirthStory(),""));
        });
        result.put("talents",talents.findBySessionIdAndGodId(sid,role).stream().filter(t->Boolean.TRUE.equals(t.getIsSelected()))
                .map(TalentEffects::view).toList());
        return result;
    }
}
