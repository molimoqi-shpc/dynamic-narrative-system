package com.bs.narrative.service;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CharacterDossierServiceTest {
 final ObjectMapper json=new ObjectMapper();final CharacterDossierService dossiers=new CharacterDossierService(mock(JdbcTemplate.class));
 @Test void generatedPsychologyCannotReplaceActualBodyOrInventPlayerThought()throws Exception{
  var own=(ObjectNode)json.readTree("{\"id\":\"a\",\"name\":\"甲\",\"body\":{\"health\":75,\"position\":\"门边\"},\"items\":[{\"ref\":\"knife\"}],\"ownRecords\":[\"过去亲眼见过渡口\"],\"establishedSelf\":[\"原有背景\"]}");
  var mind=json.readTree("{\"intention\":\"检查山亭\",\"characterTemplate\":{\"currentPsychology\":\"谨慎\",\"body\":{\"health\":999}}}");var original=own.deepCopy();var d=dossiers.assemble(own,mind,false,"",120);
  assertEquals(75,d.path("currentState").path("body").path("health").asInt());assertEquals("谨慎",d.path("psychology").path("currentPsychology").asText());assertTrue(d.path("pastInformation").toString().contains("渡口"));assertEquals(original,own);
  var p=dossiers.assemble(own,json.createObjectNode(),true,"向东观察",120);assertTrue(p.path("psychology").isEmpty());assertEquals("向东观察",p.path("playerDeclaredDirection").asText());
 }
 @Test void extrasOnlyKeepSceneDetailsAndCanBecomeCore()throws Exception{
  var own=json.createObjectNode().put("id","keeper").put("name","普通守亭人");
  own.putObject("previousPortrayal").put("narrativeTier","EXTRA");own.putObject("previousPsychology").put("roleInScene","守亭");
  var direction=(ObjectNode)json.readTree("""
   {"narrativeTier":"EXTRA","intention":"回答避雨位置","characterTemplate":{"roleInScene":"守亭","willingHelp":"允许避雨","knowledgeLimits":"只知山亭附近路况","hiddenAgenda":"错误添加的复杂秘密"}}
   """);
  var extra=dossiers.assemble(own,direction,false,"",10);assertEquals("EXTRA",extra.path("narrativeTier").asText());assertFalse(extra.path("psychology").has("hiddenAgenda"));assertEquals("允许避雨",extra.path("psychology").path("willingHelp").asText());assertFalse(extra.path("intentions").has("characterTemplate"));
  direction.put("narrativeTier","CORE");direction.withObject("characterTemplate").putObject("motivations").put("immediateGoal","参与已经发生的失踪调查");
  var promoted=dossiers.assemble(own,direction,false,"",30);assertEquals("CORE",promoted.path("narrativeTier").asText());assertTrue(promoted.path("psychology").has("motivations"));
 }
 @Test void anExistingImportantCharacterCannotLoseDetailedAnchors()throws Exception{
  var own=json.createObjectNode().put("id","a");own.putObject("previousPortrayal").put("narrativeTier","CORE");
  own.putObject("previousPsychology").putObject("values").putArray("moralLines").add("不出卖已交托的人");
  var direction=(ObjectNode)json.readTree("{\"narrativeTier\":\"EXTRA\",\"intention\":\"暂时休息\",\"characterTemplate\":{\"immediateConcern\":\"休息\"}}");
  var d=dossiers.assemble(own,direction,false,"",60);assertEquals("CORE",d.path("narrativeTier").asText());assertTrue(d.path("psychology").path("values").toString().contains("不出卖"));assertEquals("休息",d.path("psychology").path("currentSceneUpdate").path("immediateConcern").asText());
  own.remove("previousPortrayal");assertEquals("CORE",dossiers.assemble(own,direction,false,"",60).path("narrativeTier").asText(),"legacy detailed dossiers remain important");
 }
 @Test void partialOrMissingPsychologyUpdatesRetainPastSectionsAndCanExplicitlyChangeValues()throws Exception{
  var own=json.createObjectNode().put("id","a");own.putObject("previousPortrayal").put("narrativeTier","CORE");
  var previous=own.putObject("previousPsychology");previous.put("pastExperienceSummary","曾托付过一件旧物");previous.putObject("values").put("moralLine","不出卖托付者").put("currentPriority","找回旧物");previous.putObject("relationships").put("oldFriend","谨慎信任");
  var input=own.deepCopy();var partial=json.createObjectNode().put("intention","承接本轮发现");partial.putObject("characterTemplate").putObject("values").put("currentPriority","先救同伴");
  var d=dossiers.assemble(own,partial,false,"",100);assertEquals("先救同伴",d.path("psychology").path("values").path("currentPriority").asText());assertEquals("不出卖托付者",d.path("psychology").path("values").path("moralLine").asText());assertEquals("谨慎信任",d.path("psychology").path("relationships").path("oldFriend").asText());assertEquals("曾托付过一件旧物",d.path("psychology").path("pastExperienceSummary").asText());assertEquals(input,own);
  var brief=dossiers.assemble(own,json.createObjectNode().put("intention","暂不改变立场"),false,"",200);assertEquals(previous,brief.path("psychology"));assertTrue(brief.path("psychologyMeaning").asText().contains("不代表本段重新确认"));
  partial.withObject("characterTemplate").withObject("values").putNull("moralLine");assertTrue(dossiers.assemble(own,partial,false,"",300).path("psychology").path("values").path("moralLine").isNull(),"Explicit new content can replace a prior subjective belief");
 }
 @Test void playerTemplatesCannotAcquireModelSuppliedPrivatePsychology()throws Exception{
  var direction=(ObjectNode)json.readTree("{\"thought\":\"被模型擅自补出的爱慕\",\"characterTemplate\":{\"currentPsychology\":\"爱慕\"}}");
  var player=dossiers.assemble(json.createObjectNode().put("id","p"),direction,true,"普通问路",12);
  assertEquals("PLAYER",player.path("narrativeTier").asText());assertTrue(player.path("psychology").isEmpty());assertTrue(player.path("intentions").isEmpty());assertFalse(player.toString().contains("爱慕"));
 }
}
