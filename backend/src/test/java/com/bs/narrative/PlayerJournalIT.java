package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class PlayerJournalIT {
 @Autowired PlayerJournalService journal;@Autowired CharacterPanelService panel;@Autowired JdbcTemplate jdbc;
 @Autowired ItemStateService items;@Autowired GodProfileRepository actors;@Autowired KnowledgeService knowledge;
 @Autowired StoryItemOutcomes storyItems;
 @Autowired OpeningRoleMemoryService openingMemories;
 final String sid="journal_it",pid="journal_user",self="journal_self",friend="journal_friend",enemy="journal_enemy",known="journal_known";
 final ObjectMapper json=new ObjectMapper();
 @Test void eachOpeningRoleReceivesOnlyItsOwnHistoryAndExplicitInitialSharedRelationship()throws Exception{
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("relationships",List.of(Map.of("roleId",friend,"kind","FRIEND","summary","自幼相识，一起经营驿舍","basis","开局明确旧友")))),0);
  openingMemories.establish(sid,"journal_scene",json.readTree("[{\"name\":\"知秋\",\"self_history\":\"记账学徒，母亲曾掌勺\"},{\"name\":\"外人\",\"self_history\":\"FOREIGN_SESSION_SECRET\"}]"));
  var shared=openingMemories.sharedPast(sid,friend,self);assertEquals(1,shared.size());assertEquals(self,shared.get(0).path("withRoleId").asText());assertEquals("自幼相识，一起经营驿舍",shared.get(0).path("summary").asText());
  assertTrue(openingMemories.sharedPast(sid,enemy,self).isEmpty());assertTrue(openingMemories.sharedPast(sid,known,self).isEmpty());assertTrue(openingMemories.sharedPast(sid,self,self).isEmpty());
  String raw=jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='genesis:self'",String.class,sid,friend);assertTrue(raw.contains("记账学徒"));assertFalse(raw.contains("FOREIGN_SESSION_SECRET"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id='journal_foreign' AND source_ref='genesis:self'",Integer.class,sid));
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("relationships",List.of(Map.of("roleId",friend,"kind","FRIEND","summary","LATER_PLAYER_IMPRESSION","basis","后来个人判断")))),200);
  assertTrue(openingMemories.sharedPast(sid,friend,self).isEmpty(),"Later player impressions must not become another actor's original memories");
 }
 @BeforeEach void seed()throws Exception{
  for(String role:List.of(self,friend,enemy,known,"journal_foreign"))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静',?)",role,role.equals("journal_foreign")?"another_journal":sid,role.equals(self)?"你":role.equals(friend)?"知秋":role.equals(enemy)?"厉川":role.equals(known)?"船夫":"外人",role.equals(self)?"[\"木工凿\",\"麻绳\",\"月魄镜\"]":"[]");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('journal_scene',?,'渡口','[]','渡口旧景')",sid);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,?,'unused','journal_scene',?)",pid,self,pid,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,?,'journal_scene','PLAYING')",sid,pid,self);
  jdbc.update("INSERT INTO character_creation(session_id,player_id,question_1,answer_1,question_2,question_3,birth_story,status) VALUES(?,?,'出身？','出身普通木匠之家，名叫林舟，会修补简单木器。','重视什么？','如何求生？','林舟曾向船夫问路。','COMPLETED')",sid,pid);
  items.establishInitialInventory(sid,actors.findById(self).orElseThrow());
  String mirror=items.owned(sid,actors.findById(self).orElseThrow()).stream().filter(i->i.name().equals("月魄镜")).findFirst().orElseThrow().ref();
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("itemRef",mirror,"kind","ARTIFACT","description","银背旧镜，镜缘有一道裂纹","knownEffects","照见石壁上残留的灵光","limitations","只能显现已知的微弱残迹","significance","在渡口旧案中得到的唯一信物","origin","知秋在破庙中交付")),"relationships",List.of(Map.of("roleId",friend,"kind","ALLY","label","同行盟友","summary","彼此表态共同追查渡口旧案","basis","双方实际同意结盟"),Map.of("roleId",enemy,"kind","ENEMY","label","公开敌对","summary","厉川在渡口明确追杀你","basis","亲眼经历追杀")))),120);
 }
 @Test void detailedPublicAppearanceSurvivesArchiveReadsAndPartialUpdates(){
  var card=json.createObjectNode().put("roleId",friend).put("basis","亲见她持剑站在渡口").put("gender","female").put("archetype","SWORDSMAN").put("culturalStyle","EASTERN").put("apparentAge",24).put("hairColor","乌黑");
  card.putArray("clothing").add("白色广袖长衫");card.putArray("portraitKeywords").add("白玉簪").add("银线梅花");
  card.putObject("visibleAnatomy").put("leftForearm","自然血肉").put("privateHistory","不可公开的过往");
  card.putObject("visibleMount").put("present",true).put("species","unicorn").put("coat","pure-white");card.putObject("psychology").put("thought","不可公开的内心");
  journal.apply(sid,self,"journal_scene",json.createObjectNode().set("characterAppearances",json.createArrayNode().add(card)),200);
  var saved=journal.publicAppearances(sid,self).get(friend);assertEquals(24,saved.get("apparentAge"));assertEquals(List.of("白色广袖长衫"),saved.get("clothing"));assertFalse(saved.toString().contains("不可公开"));assertTrue(journal.publicAppearances(sid,known).isEmpty());
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"basis","坐骑已离开，仍是原来的黑发白衣女子","visibleMount",false)))),210);
  var next=journal.publicAppearances(sid,self).get(friend);assertEquals("乌黑",next.get("hairColor"));assertEquals(false,next.get("visibleMount"));assertEquals(24,next.get("apparentAge"));
 }
 @Test void damagedOptionalAppearanceRecordDoesNotBreakTheNextAcceptedStory(){
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"basis","亲见白衣女子","gender","female")))),200);
  jdbc.update("UPDATE role_knowledge SET content='[]' WHERE session_id=? AND god_id=? AND source_ref=?",sid,self,"appearance:"+friend);
  assertDoesNotThrow(()->journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"basis","再次亲见她穿白衣","archetype","SWORDSMAN")))),210));
  assertEquals("SWORDSMAN",journal.publicAppearances(sid,self).get(friend).get("archetype"));
 }
 @Test void newNarrativeItemAliasesBindToServerIdsWithoutDuplicatingAnExistingUniqueItem(){
  var card=json.valueToTree(Map.of("items",List.of(Map.of("itemRef","author-box","name","旧木匣","kind","KEY_ITEM","important",true,"description","内有一枚铜扣"))));
  storyItems.apply(sid,json.valueToTree(List.of(Map.of("itemRef","author-box","name","旧木匣","holderId",self,"state","HELD","source","船家从船尾取出并实际交付。"))),card);
  var box=items.owned(sid,actors.findById(self).orElseThrow()).stream().filter(i->i.name().equals("旧木匣")).findFirst().orElseThrow();assertNotEquals("author-box",box.ref());assertEquals(box.ref(),card.at("/items/0/itemRef").asText());
  journal.apply(sid,self,"journal_scene",card,150);assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("内有一枚铜扣"));
  storyItems.apply(sid,json.valueToTree(List.of(Map.of("itemRef","another-author-label","name","旧木匣","holderId",friend,"state","HELD","mode","GIFT"))));
  assertEquals(1,items.owned(sid,actors.findById(friend).orElseThrow()).stream().filter(i->i.name().equals("旧木匣")).count());assertEquals(box.ref(),items.owned(sid,actors.findById(friend).orElseThrow()).get(0).ref());
 }
 @Test void legacyNameComesFromPlayerAnswerWithoutRewritingOnRead(){
  assertEquals("林舟",panel.describe(sid,pid).get("name"));assertEquals("你",actors.findById(self).orElseThrow().getGodName());
  assertEquals("",PlayerJournalService.declaredName("师父名叫林海，父亲姓林。"));assertEquals("林舟",PlayerJournalService.declaredName("我叫林舟。"));
 }
  @Test void optionalGownIsPublicAppearanceAndDoesNotReplaceWarriorOccupation(){
   journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"gender","female","ageGroup","ADULT","archetype","WARRIOR","portraitStyle","CRIMSON_GOWN","basis","主角亲见她换上酒红长裙。")))),200);
   var card=journal.publicAppearances(sid,self).get(friend);
   assertEquals("CRIMSON_GOWN",card.get("portraitStyle"));assertEquals("WARRIOR",card.get("archetype"));
   journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"basis","容貌未变。")))),210);
   assertEquals("CRIMSON_GOWN",journal.publicAppearances(sid,self).get(friend).get("portraitStyle"));assertTrue(journal.publicAppearances(sid,enemy).isEmpty());
  }
  @Test void publicAppearanceIsOptionalObserverScopedAndRetainsItsStableFields(){
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"gender","female","ageGroup","YOUNG","divineRevealed",false,"basis","知秋是一位年轻女子，主角亲见。"),Map.of("roleId","journal_foreign","gender","female","ageGroup","CHILD","basis","别的世界的人")))),180);
  var visible=journal.publicAppearances(sid,self);assertEquals(1,visible.size());assertEquals("YOUNG",visible.get(friend).get("ageGroup"));assertEquals(false,visible.get(friend).get("divineRevealed"));
  assertTrue(journal.publicAppearances(sid,enemy).isEmpty(),"a different observer does not gain the player's knowledge");
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId",friend,"basis","本段仍是此前所见容貌"),Map.of("roleId",enemy,"gender","male")))),200);
  assertEquals("female",journal.publicAppearances(sid,self).get(friend).get("gender"));assertFalse(journal.publicAppearances(sid,self).containsKey(enemy));
 }
 @Test void meaningfulInventoryUsesAuthoredSignificanceRatherThanNameKeywords(){
  var result=panel.describe(sid,pid);var important=(List<?>)result.get("importantItems");assertEquals(1,important.size());assertTrue(important.toString().contains("照见石壁"));
  assertFalse(important.toString().contains("木工凿"));assertFalse(important.toString().contains("麻绳"));
  String rope=items.owned(sid,actors.findById(self).orElseThrow()).stream().filter(i->i.name().equals("麻绳")).findFirst().orElseThrow().ref();
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("itemRef",rope,"kind","KEEPSAKE","significance","父亲临别留下的唯一纪念")))),130);
  assertEquals(2,((List<?>)panel.describe(sid,pid).get("importantItems")).size(),"an ordinary name can become important through actual story meaning");
 }
 @Test void acquaintancesDoNotRequireLegacyTrustRowsAndPrivateAttitudesNeverLeak()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'hidden-stance','隐秘态度',?,'PLAYER_RELATION','','journal_scene','',0)",sid,friend,"{\"roleId\":\""+self+"\",\"summary\":\"不公开的欺骗计划\"}");
  var result=panel.describe(sid,pid);assertEquals(1,((List<?>)result.get("friends")).size());assertEquals(1,((List<?>)result.get("enemies")).size());assertEquals(1,((List<?>)result.get("acquaintances")).size());
  String body=json.writeValueAsString(result);assertTrue(body.contains("同行盟友"));assertTrue(body.contains("船夫"));assertFalse(body.contains("不公开的欺骗计划"));assertFalse(body.contains("journal_foreign"));
  assertFalse(knowledge.list(sid,self).toString().contains("银背旧镜"),"presentation fields do not flood the delivered-knowledge notebook");
 }
 @Test void mereNameMentionsDoNotBecomeAnInventedRecentInteraction(){
  var relations=journal.relationships(sid,self,pid);assertTrue(relations.stream().noneMatch(person->person.containsKey("lastInteraction")),"Only an actual relationship record should describe personal dealings; arbitrary chapter snippets are not interactions");
 }
 @Test void missingItemCannotBeGrantedAndPartialDetailsRemainEstablished(){
  var actor=actors.findById(self).orElseThrow();var before=List.copyOf(actor.getInventory());
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("name","从未取得的神剑","kind","ARTIFACT","knownEffects","凭空无敌")))),150);assertEquals(before,actor.getInventory());
  String mirror=items.owned(sid,actor).stream().filter(i->i.name().equals("月魄镜")).findFirst().orElseThrow().ref();
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("itemRef",mirror,"significance","旧案的线索已得到核实")))),160);
  assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("照见石壁"));
  assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("ARTIFACT"));
  items.transferNarrative(sid,actor,actors.findById(friend).orElseThrow(),mirror,"GIFT");actors.flush();
  assertTrue(((List<?>)panel.describe(sid,pid).get("importantItems")).isEmpty(),"transferred items no longer appear as held by the player");
 }
 @Test void committedNamesAndRelationsRemainOwnKnownFacts(){
  journal.synchronizeName(sid,self);assertEquals("林舟",actors.findById(self).orElseThrow().getGodName());
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'old-treaty','旧盟约',?,'TREATY','','journal_scene','',0)",sid,self,"{\"status\":\"ACTIVE\",\"proposer\":\""+self+"\",\"recipient\":\""+friend+"\"}");
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("relationships",List.of(Map.of("roleId",friend,"kind","ESTRANGED","label","旧盟已散","basis","双方在争执后实际解除盟约")))),170);
  assertTrue(((List<?>)panel.describe(sid,pid).get("friends")).isEmpty());assertEquals(2,((List<?>)panel.describe(sid,pid).get("acquaintances")).size());
 }
 @Test void seizedPossessionIsNotMislabelledAsConsensualBorrowing(){
  var victim=actors.findById(self).orElseThrow();var winner=actors.findById(enemy).orElseThrow();String ref=items.owned(sid,victim).stream().filter(i->i.name().equals("月魄镜")).findFirst().orElseThrow().ref();
  items.transferNarrative(sid,victim,winner,ref,"SEIZE");actors.flush();var acquired=items.describeOwned(sid,winner).get(0);
  assertEquals(false,acquired.get("isBorrowed"));assertEquals(true,acquired.get("isSeized"));assertTrue(acquired.get("source").toString().contains("夺取"));assertTrue(((List<?>)panel.describe(sid,pid).get("importantItems")).isEmpty());
 }
 @Test void newlyAcquiredNamedReferenceGetsDetailsButAmbiguousOrStaleRefsNeverBind(){
  var actor=actors.findById(self).orElseThrow();items.acquireNarrative(sid,actor,"映潮印","在祭台上实际取得");actors.flush();
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("itemRef","映潮印","name","映潮印","kind","KEY_ITEM","knownEffects","打开已发现的潮门")))),200);
  assertTrue(panel.describe(sid,pid).get("importantItems").toString().contains("打开已发现的潮门"));
  items.acquireNarrative(sid,actor,"同名旧钥匙","实际取得第一把");items.acquireNarrative(sid,actor,"同名旧钥匙","实际取得第二把");actors.flush();
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("items",List.of(Map.of("itemRef","同名旧钥匙","name","同名旧钥匙","knownEffects","不应猜测哪把钥匙"),Map.of("itemRef","item_stale_reference","name","映潮印","knownEffects","不应覆盖原资料")))),201);
  String cards=panel.describe(sid,pid).get("importantItems").toString();assertFalse(cards.contains("不应"));assertTrue(cards.contains("打开已发现的潮门"));
 }
 @Test void presentationJournalCannotRenameAnEstablishedOrPlayerDeclaredIdentity(){
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("playerName","错误主角名")),200);
  assertEquals("林舟",actors.findById(self).orElseThrow().getGodName());
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("playerName","另一错误名")),201);
  assertEquals("林舟",panel.describe(sid,pid).get("name"));
 }
 @Test void oldFirstPersonIntroductionIsResolvedOnlyWithMatchingBiographySubject(){
  jdbc.update("UPDATE character_creation SET answer_1='我是普通旅人林野，独自在山道旅行。',birth_story='林野生于北地一处山坳，父是樵夫。' WHERE session_id=?",sid);
  assertEquals("林野",panel.describe(sid,pid).get("name"));assertEquals("你",actors.findById(self).orElseThrow().getGodName(),"reading must not rewrite the saved identity");
  jdbc.update("UPDATE character_creation SET answer_1='我的老师是林野，我独自在山道旅行。' WHERE session_id=?",sid);assertEquals("未留名旅人",panel.describe(sid,pid).get("name"));
 }
 @Test void laterSightingsKeepSpecificRelationshipEvidence()throws Exception{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'newer-sighting','最近所见',?,'OBSERVED_STATE','','journal_scene','',0)",sid,self,json.writeValueAsString(Map.of("id",friend,"observedAtSeconds",500)));
  var connection=journal.relationships(sid,self,pid).stream().filter(r->friend.equals(r.get("godId"))).findFirst().orElseThrow();
  assertEquals("双方实际同意结盟",connection.get("basis"));assertEquals(500L,connection.get("lastSeenSeconds"));
 }
 @Test void droppingAndPickingUpSeizedLootDoesNotTurnItIntoALoan(){
  var victim=actors.findById(self).orElseThrow();var winner=actors.findById(enemy).orElseThrow();String ref=items.owned(sid,victim).stream().filter(i->i.name().equals("月魄镜")).findFirst().orElseThrow().ref();
  items.transferNarrative(sid,victim,winner,ref,"SEIZE");items.leaveInScene(sid,winner,"journal_scene",ref);items.takeFromScene(sid,winner,"journal_scene",ref);actors.flush();
  var card=items.describeOwned(sid,winner).get(0);assertEquals(true,card.get("isSeized"));assertEquals(false,card.get("isBorrowed"));
  items.transferNarrative(sid,winner,victim,ref,"RETURN");actors.flush();assertEquals(false,items.describeOwned(sid,victim).get(0).get("isSeized"));
 }
 @Test void aFinalHolderWithoutAnOwnershipAgreementDoesNotBecomeABorrower(){
  var actor=actors.findById(self).orElseThrow();String ref=items.owned(sid,actor).stream().filter(i->i.name().equals("月魄镜")).findFirst().orElseThrow().ref();
  storyItems.apply(sid,json.valueToTree(List.of(Map.of("itemRef",ref,"holderId",friend,"state","HELD"))));
  var card=items.describeOwned(sid,actors.findById(friend).orElseThrow()).get(0);assertEquals(false,card.get("isBorrowed"));assertTrue(card.get("source").toString().contains("实际转交持有"));
 }
 @Test void anUnsentAllianceResponseDoesNotSilentlyEndThePact(){
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("agreements",List.of(Map.of("kind","ALLIANCE","counterpartId",friend,"status","ACTIVE","basis","双方先前实际结盟")))),200);
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("relationships",List.of(Map.of("roleId",friend,"kind","ALLY","summary","准备联系对方解除，尚未送达")))),210);
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ACTIVE'",Integer.class,sid));
 }
 @Test void custodyAndUntypedAgreementsRemainSocialNotesRatherThanAlliances(){
  for(var entry:List.of(Map.of("kind","CUSTODY","counterpartId",friend,"status","ACTIVE","basis","实际接过映潮印代为保管，双方没有结盟"),Map.of("counterpartId",enemy,"status","ACTIVE","basis","约定只核对旧账，没有建立盟约")))journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("agreements",List.of(entry))),230);
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY'",Integer.class,sid));assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SOCIAL'",Integer.class,sid));assertTrue(knowledge.list(sid,self).toString().contains("代为保管"));
 }
 @Test void anUntypedOrdinaryAgreementCannotRenewAnEndedAlliance(){
  for(String status:List.of("ACTIVE","ENDED"))journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("agreements",List.of(Map.of("kind","ALLIANCE","counterpartId",friend,"status",status,"basis","双方明确确认盟约状态")))),240);
  journal.apply(sid,self,"journal_scene",json.valueToTree(Map.of("agreements",List.of(Map.of("counterpartId",friend,"status","ACTIVE","basis","后来只约定核对旧账")))),250);
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ENDED'",Integer.class,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='TREATY' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.status'))='ACTIVE'",Integer.class,sid));
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='SOCIAL'",Integer.class,sid));
 }
 @Test void technicalMemoryCannotCrowdOutNotebookEvidenceOrBeSharedAsNews(){
  for(int i=0;i<50;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'内部摘要','技术记忆正文','STORY_MEMORY','','journal_scene','',0)",sid,self,"internal:"+i);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'actual-clue','潮门线索','潮门在退潮后可见','PERSONAL','','journal_scene','',0)",sid,self);
  var notebook=knowledge.list(sid,self);assertEquals(1,notebook.size());assertEquals("潮门线索",notebook.get(0).get("title"));
  long metadata=jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_PROFILE' LIMIT 1",Long.class,sid,self);
  var place=new com.bs.narrative.domain.GameScene();place.setSceneId("journal_scene");place.setSessionId(sid);place.setLocationName("渡口");place.setPresentGods(List.of(friend));
  String outcome=knowledge.share(sid,self,friend,metadata,"我分享内部角色状态",place,Map.of(self,actors.findById(self).orElseThrow(),friend,actors.findById(friend).orElseThrow()),1);
  assertTrue(outcome.contains("内部角色状态不能"),outcome);
 }
}
