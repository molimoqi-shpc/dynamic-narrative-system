package com.bs.narrative.service;
import com.bs.narrative.llm.*;import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class ContinuousStoryServiceTest {
 private List<Map<String,Object>> events(){return List.of(Map.of("id","said","kind","UTTERANCE","speech","借锯吗？","text","你说：“借锯吗？”"));}
 @Test void presentationCannotInventMovementEvenIfAnotherModelWouldApproveIt(){
  var model=mock(LLMProvider.class);var result=new ContinuousStoryService(model).write("河边",events());
  assertEquals("你说：“借锯吗？”",result.text());assertFalse(result.polished());verifyNoInteractions(model);
 }
 @Test void originalQuotesAndIndependentApiProcessesRemainInTheirCausalOrder(){
  var model=mock(LLMProvider.class);var source=new ArrayList<>(events());source.add(Map.of("text","甲把刀收回袖中。乙继续查看绳结。","inner_thought","不应公开的秘密"));source.add(Map.of("text","甲说：“不借。”"));
  String result=new ContinuousStoryService(model).write("河边",source).text();assertEquals("你说：“借锯吗？”\n\n甲把刀收回袖中。乙继续查看绳结。\n\n甲说：“不借。”",result);assertFalse(result.contains("秘密"));verifyNoInteractions(model);
 }
 @Test void emptyInputDoesNotSpendMoneyOrInventAnAtmosphere(){var model=mock(LLMProvider.class);assertEquals("",new ContinuousStoryService(model).write("棚外",List.of()).text());verifyNoInteractions(model);}
 @Test void realRoundNineCannotBecomeAnUnrecordedPlayerMove()throws Exception{
  var json=new ObjectMapper();var fixture=json.readTree(getClass().getResourceAsStream("/real-regressions-20261015.json"));List<Map<String,Object>> events=json.convertValue(fixture.path("visibleEvents"),new com.fasterxml.jackson.core.type.TypeReference<>(){});
  var model=mock(LLMProvider.class);String result=new ContinuousStoryService(model).write("船棚",events).text();assertTrue(fixture.path("rejectedStory").asText().contains("棚外"));assertFalse(result.contains("棚外"));assertFalse(result.contains("开始收拾绳索"));assertTrue(result.contains("你等候的这段时间已过去"));verifyNoInteractions(model);
 }
 @Test void renderDoesNotReorderSameTickStatementsOrClipCommittedText(){var model=mock(LLMProvider.class);String longText="已发生的经过。".repeat(1500);var input=List.<Map<String,Object>>of(Map.of("text",longText,"endSeconds",5),Map.of("text","先问。","endSeconds",5),Map.of("text","后答。","endSeconds",5));assertEquals(longText+"\n\n先问。\n\n后答。",new ContinuousStoryService(model).write("任意",input).text());verifyNoInteractions(model);}
}
