package com.bs.narrative.service;

import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Source projection and transport of the main author's final manuscript, never a plot judge. */
final class StoryManuscriptReview {
 private static final ObjectMapper JSON=new ObjectMapper();
 private StoryManuscriptReview(){}
 static final String PROMPT="""
  你是唯一主API的聚焦定稿阶段，负责把尚未发表稿修成能连读的小说。paragraphs是全稿，paragraphId只是编辑用编号。actualStart是上一篇完整已发表正文，startingPoint是它真正的末尾。originStory是原始身世，worldSetting固定。playerDirection是玩家方向，decisionBoundary是本稿待玩家选择的停点。
  来源分清：原始身世、已发表正文是已成立经历；people是本人身份、当前处境与接下来意愿。候选profile和意愿不能覆盖原有亲缘、营生、生死或把拟做的事当已经发生。私密信息不自动成为角色已知。你可以直接修改未发表稿中的错误，不必让角色说纠错台词，更不能改写已发表前文来替新稿圆谎。
  完整读过全稿后，先给editorialReview，五类各自读，不让一类代替其他类：
  identity：只取正文实际用到的称谓、身份、生死及相关过往，按说话者与所指者核对原始来源。亲属尚在世时不可被顺口写成有牌位的死者。年龄、信物何时交到本人手里、已经做过哪些修理也来自原始身世和已发表经历，不能为顺口的“十年”“上回修好”另外补一段历史；同一未发表候选中的结果仍须在本篇发生。
  repeatedResults：按同一目标跨段对读，不按演员分别概述。有人获救、抵达、交付完成后，后来另一个人的回报必须接上结果；未明确改为其他人时，“去背某位老人→背一位老妇到安全处→又说运出该老人”就是同一目标，不能靠凭空解释另一个老妇或又一次转移来圆。普通重复可删改，实际灾损等后果保留。
  knowledgeOrder：同行者在场已经听见条件、答复或证据，回来后可以商议、复盘、分工，不再演第一次被告知。不从未发表候选结尾接写历史。
  evidenceAndInference必须先分列原始依据，而不是先接受人物的解释：[{sourceQuote:"记录或实物的原句",object:"实际记什么",recordType:"OBSERVATION/RULE/TRANSACTION/CLAIM",value:"原数量与单位或规则",sameOrDifferentFrom:"与哪项同类依据相同/不同",supports:"确能推出什么",unsupportedClaims:"哪些推论没有依据"}]。最后才给结论。某日的“某时闭闸，水至某阶”是行动日志，不自动成为“规定水至某阶才闭闸”的规则。若只有观测值写高，没有闭闸规则原文的前后变化，就不能靠后来几个人都说“线被抬高”宣布阈值被改。谁说过不等于证实，不能为了让推论好听而调换记录类型。三份记录两份相同就是两种数值；每月初一的记录不能不交代更新就变成月中记录。收付主体与单位同样对读。直接修本稿相关原句及其后续复述，让实际可证的发现保留、尚待查明的仍未知，不制造新证据、犯人或交易，不替玩家追查新方向。
  chronology：摘startingPoint实际末尾与本稿实际末尾，给起讫时段和确有的过夜/多日跨度。稿首的“次日/第二天”要接上一章末尾理解，若上一章已经清晨，不可默默换成前夜；应修清指代或如实计入真正又过的一夜。不只看首尾：同篇当晨已经归还的物品，傍晚不能复述为昨夜归还；角色说的昨夜、昨日、刚才都对照其所指事件实际发生的段落。连续“几日后”“半个月后”按各自承接处理解，若实际是在概述同一范围，就在正文明确范围，不能在耗时表暗中把它们当重叠而让普通读者仍读作又过一段。概述覆盖范围清楚，后续场景不无故倒回去。同一场景接着几句对话、点头、望一眼仍是同一时段，不凭空加半天等待。正常日常省略保持省略，不补流水账。
  每类只列真正影响理解的原句与结论；无问题可空，不堆无关身份或琐事。editorialReview是同次修改依据，不进入正文或世界书。
  然后返回paragraphRevisions:[{paragraphId:"原编号",text:"此段修订后的完整文字，保留未改部分",reason:"具体原因"}]。只交需要改的段落，每个编号最多一次，服务器逐字保留其他段落。无须修改就空数组。删无意义重复段可给空text。不重写整篇、不返回publication。已经发现的问题落实到这些段落，保留文风、真实后果、人物立场和重大选择，不替玩家接受新责任、结盟或风险，不续写新情节。
  根层另给chronologyChanged:true/false。只有你在paragraphRevisions里实际修改了事件先后、过夜/跨日或起讫时段，才为true；称谓、语句、身份和证据表述的修订不改变时间。未改时序时false，通常沿用authoredTimeProgress，不再盲估一遍耗时。确实改了时序才返回与新正文对应的timeProgress及calendarInterval。
  同次chronology阅读也对照authoredTimeProgress：它是主创记录，可能漏记跨度或错用单位。仅当能从实际正文指出明确矛盾时，另给timingCorrection:{required:true,reason:"原记录与正文具体哪里矛盾",sourceQuotes:["最终正文逐字短句"]}及覆盖全篇的正确timeProgress；正文时序无需修改时chronologyChanged仍为false。这是纠正记录，不是另写人生。原记录合理或只是另一种模糊估计时不标required，不用自己的估时覆盖它。比如正文跨过数十年而原记录却为30 DAY，就应记录整段年数；不能只取最后冬天或结尾对话的时长。
  再返回timeProgress:{scope:"THIS_PASSAGE",milestones:[{event:"真正跨时段的少量转折",sourceQuote:"最终稿短句",elapsedPartsFromStart:[{amount:从startingPoint末尾0算到此处实际累计的非负数量,unit:"YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND"}]}],to:"真实最终停点",basis:"实际起讫与新增间隔"}。同场接续归同一转折；最后一项覆盖真正末尾，程序只取最后累计数，绝不将各累计数相加。日期编号不当经过的整日，午后至翌晨约十几小时，模糊也估到小时，不向上凑整天。明确经过多年则用YEAR记录全部年月，不缩为DAY。一个时辰2小时，只给原数量与单位，不手算秒数；不迁就玩家预计时间。
  最后返回cast:[{roleId:"已有或候选编号，漏建档者留空",name:"已确立姓名/稳定称呼",profile:"仅漏建档者写本篇开始前的身份和过往，简短；本稿拟发生的修理、交付、答应和问答不写成其已有经历",narrativeTier:"CORE或EXTRA",relationToPlayer:{kind:"FAMILY/FRIEND/ACQUAINTANCE/ALLY/ENEMY/RIVAL/ESTRANGED",label:"具体关系",basis:"玩家已知的明确来源",summary:"已成立关系"}}]。仅列实际行动、直接交谈者；不把背景人群、提及死者、牌位建角色，不为配图扩充人物。既有人用characterIndex/people真实编号，不以姓名猜编号。亲人实际参与一样列出，姓名沿用原始来源，不用别称重建。漏建档者先由服务器取得本人意愿，再定稿。
  relationToPlayer只记他人与玩家实际已知关系，玩家本人不填。existingRelationships未变者省略关系子对象。已明确亲缘写FAMILY及具体称谓，不降成相识；普通帮忙不是正式结盟，未知敌意不是已成立仇敌。龙套用EXTRA简版。只返回上述JSON，不填物品、剧情卡、账目或世界书；归档随后只记录这份唯一终稿及其时间。
  """;
 static void enrich(ObjectNode input,ObjectNode facts,ObjectNode draft){
  input.set("playerDirection",facts.path("playerDirection").deepCopy());
  input.set("worldSetting",facts.at("/currentWorld/worldSetting").deepCopy());
  input.set("originSource",facts.path("originSource").deepCopy());
  input.put("reviewFormat","PARAGRAPH_REVIEW_V3_TIMING_RECORD");
  input.set("authoredTimeProgress",draft.path("timeProgress").deepCopy());
  input.remove("story");var paragraphs=input.putArray("paragraphs");
  for(var part:paragraphs(draft.path("story").asText()))paragraphs.addObject().put("paragraphId",part.id()).put("text",part.text());
  String previous=StoryAuthorContext.latestPublishedPassage(facts).asText();
  input.putObject("startingPoint").put("publishedEnding",previous.substring(Math.max(0,previous.length()-900))).put("meaning","这里是actualStart的真正末尾，只从此处之后写作和累计时间；前文出发、行程、问答均已完成，不从其开头或中途重新计时。");
  var endpoint=StoryContinuationContext.publishedEndpoint(facts);if(!endpoint.isEmpty())input.withObject("startingPoint").set("publishedEndpoint",endpoint);
  if(facts.path("originStory").isTextual())input.set("originStory",facts.path("originStory").deepCopy());
  input.set("characterIndex",facts.path("characterIndex").deepCopy());
  var relationships=input.putArray("existingRelationships");
  if(facts.at("/playerJournal/relationships").isArray())relationships.addAll((ArrayNode)facts.at("/playerJournal/relationships").deepCopy());
  for(var record:facts.at("/playerJournal/records"))if("PLAYER_RELATION".equals(record.path("source_type").asText())){
   var content=record.path("content");if(content.isTextual())try{content=ModelOutput.object(content.asText());}catch(IllegalArgumentException unreadable){continue;}
   if(content.isObject())relationships.add(content.deepCopy());
  }
  input.set("proposedCharacters",draft.path("newCharacters").deepCopy());
  input.set("committedProgress",StoryContinuationContext.committedTurnProgress(facts));
  var people=input.putArray("people");
  for(var entry:facts.path("characterDossiers").properties()){
   var person=people.addObject().put("roleId",entry.getKey());var dossier=entry.getValue();
   for(String field:List.of("name","isPlayer"))if(dossier.has(field))person.set(field,dossier.path(field).deepCopy());
   var wishes=person.putObject("intentions");for(String field:List.of("intention","boundaries","communicationIntent","itemIntentions"))if(dossier.path("intentions").has(field))wishes.set(field,dossier.path("intentions").path(field).deepCopy());
   person.set("originalIdentity",dossier.at("/identity/establishedSelf").deepCopy());
   person.set("lastSituation",dossier.at("/currentState/storySituation").deepCopy());
   person.set("body",dossier.at("/currentState/body").deepCopy());
  }
 }
 private record Paragraph(String id,String text,String separator){}
 private static List<Paragraph> paragraphs(String story){
  var result=new ArrayList<Paragraph>();var matches=java.util.regex.Pattern.compile("\\r?\\n(?:[\\t ]*\\r?\\n)+").matcher(story);int from=0;
  while(matches.find()){result.add(new Paragraph("P"+(result.size()+1),story.substring(from,matches.start()),matches.group()));from=matches.end();}
  result.add(new Paragraph("P"+(result.size()+1),story.substring(from),""));return result;
 }
 /** Apply only author-addressed paragraph IDs; no prose matching or plot judgement. */
 static ObjectNode reviewedReply(ObjectNode reply,String original){
  if(!reply.path("paragraphRevisions").isArray())return reply;
  var parts=paragraphs(original);var known=new HashSet<String>();parts.forEach(part->known.add(part.id()));var replacements=new HashMap<String,String>();
  for(var revision:reply.path("paragraphRevisions")){
   String id=revision.path("paragraphId").asText();
   if(!known.contains(id) || !revision.path("text").isTextual() || replacements.putIfAbsent(id,revision.path("text").asText())!=null)
    throw new IllegalArgumentException("定稿段落编号缺失、重复或文字结构不完整；原回复已保留，只续接定稿");
  }
  var result=reply.deepCopy();var publication=result.putObject("publication");
  if(replacements.isEmpty())publication.put("unchanged",true);
  else{var text=new StringBuilder();for(var part:parts)text.append(replacements.getOrDefault(part.id(),part.text())).append(part.separator());if(text.toString().isBlank())throw new IllegalArgumentException("定稿不能删除整篇正文；原稿和回复已保留");publication.put("story",text.toString());}
  return result;
 }
 static String source(ObjectNode facts,ObjectNode draft){
  var input=JSON.createObjectNode();input.set("draft",draft.deepCopy());enrich(input,facts,draft);
  input.set("actualStart",StoryAuthorContext.latestPublishedPassage(facts));
  return StoryArchiveService.source(input);
 }
 static ObjectNode apply(ObjectNode manuscript,JsonNode publication,ObjectNode facts){
  var out=manuscript.deepCopy();var cast=publication.at("/manuscriptReview/cast");
  if(!cast.isArray())return out;
  var names=new HashMap<String,Set<String>>();var ids=new HashSet<String>();
  for(var entry:facts.path("characterIndex"))index(names,ids,entry.path("name").asText(),entry.path("roleId").asText());
  for(var entry:facts.at("/currentWorld/resources").properties())index(names,ids,entry.getValue().path("name").asText(),entry.getKey());
  var roles=out.withArray("roleIds");var existingRoles=new HashSet<String>();roles.forEach(x->existingRoles.add(x.asText()));
  for(var person:cast){
   String name=person.path("name").asText(),role=person.path("roleId").asText();
   if(name.isBlank())continue;
   if(!ids.contains(role))role=names.getOrDefault(name,Set.of()).size()==1?names.get(name).iterator().next():"";
   if(!role.isBlank()){
    if(existingRoles.add(role))roles.add(role);
   }else{
    boolean declared=false;for(var proposed:out.path("newCharacters"))if(name.equals(proposed.path("name").asText()))declared=true;
    if(!declared && !person.path("profile").asText().isBlank()){
     var fresh=out.withArray("newCharacters").addObject().put("name",name).put("profile",person.path("profile").asText());
     fresh.put("narrativeTier","CORE".equals(person.path("narrativeTier").asText())?"CORE":"EXTRA");
    }
   }
   var relation=person.path("relationToPlayer");
   if(relation.isObject() && !relation.path("kind").asText().isBlank() && !relation.path("basis").asText().isBlank()){
    String targetId=role.isBlank()?name:role;ObjectNode row=null;
    for(var saved:out.path("manuscriptRelationships"))if(saved instanceof ObjectNode object && targetId.equals(saved.path("roleId").asText())){row=object;break;}
    if(row==null)row=out.withArray("manuscriptRelationships").addObject().put("roleId",targetId);
    for(String field:List.of("kind","label","basis","summary"))if(relation.path(field).isTextual())row.set(field,relation.path(field).deepCopy());
   }
  }
  StoryCastService.bindNamedReferences(out,facts);return out;
 }
 private static void index(Map<String,Set<String>> names,Set<String> ids,String name,String id){
  if(name.isBlank() || id.isBlank())return;ids.add(id);names.computeIfAbsent(name,k->new HashSet<>()).add(id);
 }
 static void mergeRelationships(ObjectNode out,ObjectNode facts){
  if(!out.path("manuscriptRelationships").isArray())return;
  StoryCastService.bindNamedReferences(out,facts);var journal=out.withObject("playerJournal");var rows=journal.withArray("relationships");
  var unique=new LinkedHashMap<String,ObjectNode>();
  for(var row:rows)if(row instanceof ObjectNode object && !row.path("roleId").asText().isBlank()){
   String id=row.path("roleId").asText();var merged=unique.computeIfAbsent(id,k->JSON.createObjectNode());
   for(var field:object.properties())if(!field.getValue().isNull())merged.set(field.getKey(),field.getValue().deepCopy());
  }
  for(var relation:out.path("manuscriptRelationships")){
   String id=relation.path("roleId").asText();if(id.isBlank())continue;
   var target=unique.computeIfAbsent(id,k->JSON.createObjectNode().put("roleId",id));
   for(String field:List.of("kind","label","basis","summary"))if(relation.has(field))target.set(field,relation.path(field).deepCopy());
  }
  rows.removeAll();unique.values().forEach(rows::add);
 }
}
