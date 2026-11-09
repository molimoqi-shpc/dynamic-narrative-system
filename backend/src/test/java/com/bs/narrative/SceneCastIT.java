package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.domain.GameScene;
import com.bs.narrative.llm.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class SceneCastIT {
 @org.springframework.boot.test.mock.mockito.MockBean LLMProvider model;
 @Autowired SceneCastService cast;@Autowired JdbcTemplate jdbc;
 @Test void proseActorsBecomeOwnedActorsButInventedNamesAreRejected(){
  String sid="cast_it",source="陈道人与黑衣女修各据坑边一方，猎户们持械瑟缩。";
  GameScene scene=new GameScene();scene.setSceneSummary(source);scene.setPresentGods(new ArrayList<>());
  String j="{\"scene_cast\":[{\"name\":\"陈道人\",\"basis\":\""+source+"\"},{\"name\":\"黑衣女修\",\"basis\":\""+source+"\"},{\"name\":\"新来的天帝\",\"basis\":\"新来的天帝站在坑口\"}]}";
  var ids=cast.recover(sid,scene,new LLMResponse(j,j,0,0,0,0),source);assertEquals(3,ids.size());assertEquals(ids,scene.getPresentGods());
  assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
  assertTrue(ids.stream().allMatch(id->sid.equals(jdbc.queryForObject("SELECT session_id FROM god_profile WHERE god_id=?",String.class,id))));
  assertEquals(ids,cast.recover(sid,scene,new LLMResponse(j,j,0,0,0,0),source));
 }
 @Test void absentEvidenceAndPlayerRequestsCannotPopulateScene(){
  GameScene scene=new GameScene();scene.setSceneSummary("四下无人");scene.setPresentGods(new ArrayList<>());
  String j="{\"scene_cast\":[{\"name\":\"天帝\",\"basis\":\"我让天帝出现\"}]}";
  assertTrue(cast.recover("cast_empty",scene,new LLMResponse(j,j,0,0,0,0),"四下无人").isEmpty());
  assertTrue(cast.recover("cast_empty",scene,new LLMResponse(j,j,0,0,0,0),"").isEmpty());
 }

 @Test void omittedCastOnObservationIsRecoveredByOneSourceOnlyRequest(){
  GameScene scene=new GameScene();scene.setSceneSummary("陈道人留在坑口，猎户们还在另一侧。");scene.setPresentGods(new ArrayList<>());
  String j="{\"scene_cast\":[{\"name\":\"陈道人\",\"basis\":\"陈道人留在坑口\"},{\"name\":\"猎户们\",\"basis\":\"猎户们还在另一侧\"}]}";
  org.mockito.Mockito.when(model.chatForJson(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call->{java.util.List<java.util.Map<String,String>> msgs=call.getArgument(0);assertFalse(msgs.toString().contains("让天帝出现"));return new LLMResponse(j,j,0,0,0,0);});
  var ids=cast.recoverWithRetry("cast_retry",scene,new LLMResponse("{\"intent\":\"OBSERVE\"}","{\"intent\":\"OBSERVE\"}",0,0,0,0),scene.getSceneSummary());assertEquals(2,ids.size());org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).chatForJson(org.mockito.ArgumentMatchers.anyList());
 }

 @Test void explicitOpeningRosterCannotBePartiallyDroppedOrDuplicatedByModel(){
  GameScene scene=new GameScene();String source="赵家私兵、散修陈道人、黑衣女修各据一方，猎户们持械瑟缩。";scene.setSceneSummary(source);scene.setPresentGods(new ArrayList<>());
  String j="{\"scene_cast\":[{\"name\":\"陈道人\",\"basis\":\""+source+"\"}]}";
  var ids=cast.recoverWithRetry("literal_cast",scene,new LLMResponse(j,j,0,0,0,0),source);assertEquals(4,ids.size());
  org.mockito.Mockito.verifyNoInteractions(model);
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id='literal_cast' AND god_name LIKE '%陈道人'",Integer.class));
 }
 @Test void rumorOrSceneryIsNotAConfirmedRoster(){
  assertTrue(SceneCastService.literalRoster("传闻赵家私兵、陈道人、黑衣女修各据一方。").isEmpty());
  assertTrue(SceneCastService.literalRoster("古树、青石、石雕各据一方，女修雕像站在坑边。").isEmpty());
 }
}
