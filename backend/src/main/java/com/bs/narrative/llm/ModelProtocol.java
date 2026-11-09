package com.bs.narrative.llm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** One versioned transport definition for prompts and decoding. No plot decisions. */
public final class ModelProtocol {
 public enum Stage { GENERAL, WORLD, TALENTS, BIRTH, OPENING_ITEMS, OBJECTIVES, ROLE, AUTHOR, DIRECT_AUTHOR, STORY_FRAME, STORY_TEXT, REVIEW, ARCHIVE, ARCHIVE_EDIT, ACCOUNTS, TIME, PUBLICATION, ENDING }
 private static final ObjectMapper JSON=new ObjectMapper();
 public static final Map<String,Long> SECONDS_PER_UNIT=Map.of("SECOND",1L,"MINUTE",60L,"HOUR",3600L,"SHICHEN",7200L,"DAY",86400L,"WEEK",604800L,"MONTH",2592000L,"YEAR",31536000L);
 public static final List<String> CORE_SECTIONS=List.of("selfConcept","personality","values","emotionalState","cognition","motivations","relationships","memoryInterpretation","expression","cooperationAndConflict","actionPlan","continuity");
 private static final String MARKER="【统一数据协议 MODEL_PROTOCOL_V1/";
 private static final String COMMON="""
  仅交付一个完整JSON根对象，不输出Markdown围栏、第二个对象、解释前后缀或省略号。字段名及字符串用英文双引号，正文内双引号与换行按JSON转义；数字和布尔值使用原生类型。示例中的说明文字不是需要照抄的内容。先完整生成长文字符串，再结束该对象，不把未写完的字段接到根对象之外。
  下方schema统一定义本阶段字段的类型。可选且无变化的字段省略；需要表达空集合用[]或{}，不把数组写成文字或把对象写成JSON字符串。已有兼容字段无需再输出第二份；正文、意愿、已发生结果各按本阶段职责，不混成同一事实。编号只引用相应索引，不拿人物编号充作地点编号，不自行编造服务器编号。普通描述写在文字字段，不放入编号字段。
  schema是交接格式，不决定剧情成败或人物是否抵达；实际内容由本阶段职责、角色本人经历及唯一已发表正文决定。不要为了凑格式制造新事件或删掉真实结果。未知资料按各字段允许的空值处理，不伪造已知事实。
  schema外层的type、properties、required是字段定义，不是回复外壳；把真实字段和值直接放在JSON根对象，不把故事数据装进properties、data或result，也不要返回字段定义本身。
  """;
 private static final EnumMap<Stage,ObjectNode> SCHEMAS=new EnumMap<>(Stage.class);
 static {
  for(var stage:Stage.values())SCHEMAS.put(stage,obj());
  fields(Stage.WORLD,"title genre culturalStyle era introduction summary namingBasis","questions boundaries");
  fields(Stage.TALENTS,"","personality_tags talents");
  item(Stage.TALENTS,"talents",properties("talent_name:s talent_description:s power_level:n stat_key:s"));
  ((ObjectNode)SCHEMAS.get(Stage.TALENTS).at("/properties/talents/items/properties")).set("special_effect",properties("trigger:s benefit:s limits:s tradeoff:s"));
  SCHEMAS.get(Stage.TALENTS).withObject("properties").withObject("talents").put("minItems",3);
  fields(Stage.BIRTH,"birth_story player_name story_time first_event_description","");
  var location=properties("location_name:s scene_description:s present_npcs:a scene_cast:a coord_x:n coord_y:n");
  location.withObject("properties").withObject("scene_cast").set("items",properties("name:s basis:s self_history:s"));
  field(Stage.BIRTH,"initial_location",location);
  item(Stage.BIRTH,"initial_inventory",properties("owner:s items:a"));SCHEMAS.get(Stage.BIRTH).withObject("properties").withObject("initial_inventory").put("x-legacy-type","object");
  field(Stage.BIRTH,"player_journal",journal());
  field(Stage.BIRTH,"initial_moment",properties("year:n day:n period:s minuteOfDay:n"));
  field(Stage.OPENING_ITEMS,"initial_inventory",SCHEMAS.get(Stage.BIRTH).at("/properties/initial_inventory").deepCopy());
  fields(Stage.OBJECTIVES,"","objectives");item(Stage.OBJECTIVES,"objectives",properties("objective_id:s title:s current_step:s rule:o"));
  fields(Stage.ROLE,"thought intention actionDirection communicationIntent boundaries narrativeTier tierReason","itemIntentions");
  for(String key:List.of("characterTemplate","contest","combatDisposition"))field(Stage.ROLE,key,type("object"));
  var template=obj();for(String section:CORE_SECTIONS)template.withObject("properties").set(section,type("relationships".equals(section)?"array":"object"));
  for(String section:List.of("roleInScene","immediateConcern","attitude","willingHelp","knowledgeLimits","boundaries"))template.withObject("properties").set(section,type("string"));
  field(Stage.ROLE,"characterTemplate",template);
  item(Stage.ROLE,"itemIntentions",properties("itemRef:s intention:s targetId:s conditions:s"));
  fields(Stage.AUTHOR,"story stop continuity","roleIds newCharacters roleRequests plotRequests worldBookRequests mechanics");
  SCHEMAS.get(Stage.AUTHOR).withObject("properties").withObject("roleIds").set("items",type("string"));
  var requested=properties("roleId:s");requested.put("x-legacy-type","string");item(Stage.AUTHOR,"roleRequests",requested);
  item(Stage.AUTHOR,"worldBookRequests",properties("roleId:s beforeEntryId:n limit:n"));
  item(Stage.AUTHOR,"plotRequests",properties("eventId:s threadId:s beforeEntryId:n limit:n"));
  item(Stage.AUTHOR,"mechanics",properties("actorId:s targetId:s kind:s statKey:s"));
  field(Stage.AUTHOR,"lifeEnding",properties("kind:s playerRequested:b fulfilled:b summary:s"));
  var plan=JSON.createObjectNode();plan.putArray("type").add("object").add("string");field(Stage.AUTHOR,"authorPlan",plan);
  field(Stage.AUTHOR,"decision",properties("type:s question:s stakes:s counterpartId:s"));field(Stage.AUTHOR,"timeProgress",time());
  item(Stage.AUTHOR,"newCharacters",properties("name:s profile:s narrativeTier:s existingRoleId:s"));
  fields(Stage.REVIEW,"","paragraphRevisions cast");
  field(Stage.REVIEW,"chronologyChanged",type("boolean"));field(Stage.REVIEW,"editorialReview",type("object"));
  field(Stage.REVIEW,"publication",properties("story:s unchanged:b"));field(Stage.REVIEW,"timeProgress",time());
  field(Stage.REVIEW,"timingCorrection",properties("required:b reason:s sourceQuotes:a"));
  item(Stage.REVIEW,"paragraphRevisions",properties("paragraphId:s text:s reason:s"));
  item(Stage.REVIEW,"cast",properties("roleId:s name:s profile:s narrativeTier:s relationToPlayer:o"));
  fields(Stage.ARCHIVE,"continuity","endingCast sceneOutcomes itemOutcomes roleExperiences contestResolutions newCharacterExperiences knownPlotReviews objectiveUpdates events");
  for(String key:List.of("continuityState","playerJournal","plotUpdates","playerViewUpdates","endingPlaces"))field(Stage.ARCHIVE,key,type("object"));
  field(Stage.ARCHIVE,"playerJournal",journal());
  field(Stage.ARCHIVE,"timeProgress",time());field(Stage.ARCHIVE,"publication",properties("story:s unchanged:b timeProgress:o"));
  item(Stage.ARCHIVE,"endingCast",properties("roleId:s placeKey:s basis:s"));
  SCHEMAS.get(Stage.ARCHIVE).withObject("properties").withObject("endingPlaces").set("additionalProperties",properties("locationName:s opening:s"));
  item(Stage.ARCHIVE,"roleExperiences",properties("roleId:s experience:s situation:s"));
  item(Stage.ARCHIVE,"itemOutcomes",properties("itemRef:s name:s state:s holderId:s mode:s source:s scene:o sceneId:s"));
  var direct=SCHEMAS.get(Stage.AUTHOR).deepCopy();var directFields=direct.withObject("properties");
  for(String field:List.of("endingPlaces","endingCast","itemOutcomes","roleExperiences","contestResolutions","playerJournal","continuityState","plotUpdates","objectiveUpdates","events"))directFields.set(field,SCHEMAS.get(Stage.ARCHIVE).at("/properties/"+field).deepCopy());
  directFields.withObject("newCharacters").set("items",properties("name:s profile:s narrativeTier:s existingRoleId:s experience:s situation:s"));
  SCHEMAS.put(Stage.DIRECT_AUTHOR,direct);
  var frame=direct.deepCopy();var frameFields=frame.withObject("properties");frameFields.remove(List.of("story","timeProgress"));
  frameFields.set("startMoment",properties("year:n day:n period:s minuteOfDay:n"));frameFields.set("endMoment",properties("year:n day:n period:s minuteOfDay:n"));
  var scene=properties("key:s content:s place:s kind:s at:o");scene.withObject("properties").set("at",properties("year:n day:n period:s minuteOfDay:n"));var sceneList=type("array");sceneList.set("items",scene);frameFields.set("scenes",sceneList);SCHEMAS.put(Stage.STORY_FRAME,frame);
  fields(Stage.ACCOUNTS,"continuity","knownPlotReviews");field(Stage.ACCOUNTS,"continuityState",type("object"));
  field(Stage.TIME,"timeProgress",time());field(Stage.PUBLICATION,"timeProgress",time());field(Stage.PUBLICATION,"publication",properties("story:s unchanged:b"));
  fields(Stage.ENDING,"death_summary final_evaluation","");
  SCHEMAS.put(Stage.ARCHIVE_EDIT,SCHEMAS.get(Stage.ARCHIVE).deepCopy());
 }
 private ModelProtocol(){}
 private static ObjectNode obj(){var n=type("object");n.putObject("properties");return n;}
 private static ObjectNode type(String type){return JSON.createObjectNode().put("type",type);}
 private static ObjectNode properties(String declaration){
  var n=obj();for(String entry:declaration.split(" "))if(!entry.isBlank()){
   var pair=entry.split(":");String t=switch(pair[1]){case "s"->"string";case "a"->"array";case "n"->"number";case "b"->"boolean";default->"object";};
   n.withObject("properties").set(pair[0],type(t));
  }return n;
 }
 private static void fields(Stage stage,String strings,String arrays){
  for(String key:strings.split(" "))if(!key.isBlank())field(stage,key,type("string"));
  for(String key:arrays.split(" "))if(!key.isBlank())field(stage,key,type("array"));
 }
 private static void field(Stage stage,String key,ObjectNode value){SCHEMAS.get(stage).withObject("properties").set(key,value);}
 private static void item(Stage stage,String key,ObjectNode value){var a=type("array");a.set("items",value);field(stage,key,a);}
 private static ObjectNode time(){
  var t=properties("scope:s from:s to:s dayNumbering:s basis:s elapsedParts:a milestones:a calendarInterval:o elapsedSeconds:n");
  var part=properties("amount:n unit:s");var units=part.withObject("properties").withObject("unit").putArray("enum");new TreeSet<>(SECONDS_PER_UNIT.keySet()).forEach(units::add);
  t.withObject("properties").withObject("elapsedParts").set("items",part);return t;
 }
 private static ObjectNode journal(){
  var j=properties("playerName:s items:a relationships:a agreements:a characterAppearances:a");
  j.withObject("properties").withObject("items").set("items",properties("itemRef:s name:s kind:s important:b description:s knownEffects:s limitations:s significance:s origin:s"));
  j.withObject("properties").withObject("relationships").set("items",properties("roleId:s name:s kind:s label:s summary:s basis:s"));
  j.withObject("properties").withObject("agreements").set("items",properties("kind:s counterpartId:s proposerId:s status:s basis:s terms:s"));
  j.withObject("properties").withObject("characterAppearances").put("x-presentation-only",true);
  return j;
 }
 public static ObjectNode schema(Stage stage){return SCHEMAS.get(stage).deepCopy();}
 /** New requests see one canonical form. Old fields remain readable, not advertised as alternatives. */
 public static ObjectNode canonicalSchema(Stage stage){
  if(stage==Stage.STORY_TEXT)return type("string").put("contentMediaType","text/plain").put("description","按已确定场景框架生成完整纯正文；此阶段使用chat文本传输，不请求JSON对象。");
  var out=schema(stage);var properties=out.withObject("properties");
  if(stage==Stage.REVIEW)properties.remove("publication");
  if(stage==Stage.ARCHIVE)properties.remove(List.of("publication","timeProgress","sceneOutcomes"));
  String required=switch(stage){
   case WORLD->"title genre culturalStyle era introduction summary boundaries questions";
   case TALENTS->"personality_tags talents";
   case BIRTH->"birth_story player_name initial_location story_time initial_inventory player_journal first_event_description";
   case OPENING_ITEMS->"initial_inventory";case OBJECTIVES->"objectives";
   case ROLE->"thought intention actionDirection communicationIntent boundaries narrativeTier tierReason characterTemplate";
   case REVIEW->"paragraphRevisions chronologyChanged cast";case ARCHIVE->"continuity endingPlaces endingCast";
   case ACCOUNTS->"continuityState knownPlotReviews continuity";case TIME->"timeProgress";
   case PUBLICATION->"publication timeProgress";case ENDING->"death_summary final_evaluation";default->"";
  };
  if(!required.isBlank()){var keys=out.putArray("required");for(String key:required.split(" "))keys.add(key);}
  if(stage==Stage.AUTHOR || stage==Stage.DIRECT_AUTHOR){var options=out.putArray("anyOf");var complete=options.addObject().putArray("required").add("story").add("stop").add("timeProgress");if(stage==Stage.DIRECT_AUTHOR)complete.add("roleIds").add("continuity").add("endingPlaces").add("endingCast").add("roleExperiences");for(String query:List.of("roleRequests","plotRequests","worldBookRequests","newCharacters","mechanics"))options.addObject().putArray("required").add(query);}
  if(stage==Stage.STORY_FRAME){
   var ending=properties.withObject("lifeEnding");ending.withObject("properties").withObject("kind").putArray("enum").add("ONGOING").add("RETIREMENT");ending.putArray("required").add("kind").add("playerRequested").add("fulfilled");
   var options=out.putArray("anyOf");options.addObject().putArray("required").add("endMoment").add("scenes").add("stop").add("roleIds").add("endingPlaces").add("endingCast").add("roleExperiences").add("lifeEnding");for(String query:List.of("roleRequests","plotRequests","worldBookRequests","newCharacters","mechanics"))options.addObject().putArray("required").add(query);
  }
  if(stage==Stage.ROLE)properties.withObject("narrativeTier").putArray("enum").add("CORE").add("EXTRA");
  if(stage==Stage.AUTHOR || stage==Stage.DIRECT_AUTHOR)properties.withObject("stop").putArray("enum").add("RESOLVED").add("CONTINUE").add("DECISION");
  stripCompatibility(out);return out;
 }
 private static void stripCompatibility(JsonNode value){
  if(value instanceof ObjectNode object)object.remove(List.of("x-legacy-type","x-presentation-only"));for(var child:value)if(child.isContainerNode())stripCompatibility(child);
 }
 /** Copy frozen messages; preserve their order, data and all existing instructions. */
 public static List<Map<String,String>> messages(Stage stage,List<Map<String,String>> original){
  if(stage==Stage.STORY_TEXT)return original.stream().map(Map::copyOf).toList();
  var out=new ArrayList<Map<String,String>>();boolean applied=false;
  for(var message:original){
   var copy=new LinkedHashMap<>(message);
   if("system".equals(copy.get("role")) && !applied){
    String text=Objects.toString(copy.get("content"),"");
    if(!text.contains(MARKER))copy.put("content",text+"\n"+MARKER+stage+"】\n"+COMMON+"\n"+canonicalSchema(stage));
    applied=true;
   }out.add(Collections.unmodifiableMap(copy));
  }
  if(!applied)out.add(0,Map.of("role","system","content",MARKER+stage+"】\n"+COMMON+"\n"+canonicalSchema(stage)));
  return List.copyOf(out);
 }
 public static Stage policyStage(LLMRequestPolicy policy){return switch(policy){
  case CHARACTER->Stage.ROLE;case STORY_AUTHOR->Stage.AUTHOR;case DIRECT_STORY->Stage.DIRECT_AUTHOR;case STORY_FRAME->Stage.STORY_FRAME;case STORY_TEXT->Stage.STORY_TEXT;case STORY_REVIEW->Stage.REVIEW;
  case STORY_ARCHIVE->Stage.ARCHIVE;case ACCOUNT_ARCHIVE->Stage.ACCOUNTS;
  case STORY_TIME->Stage.TIME;case STORY_PUBLICATION->Stage.PUBLICATION;default->Stage.GENERAL;
 };}
 public static boolean hasSpecificStage(List<Map<String,String>> messages){
  return messages.stream().filter(message->"system".equals(message.get("role"))).map(message->Objects.toString(message.get("content"),"")).anyMatch(text->text.contains(MARKER) && !text.contains(MARKER+Stage.GENERAL+"】"));
 }
 /** Old cached replies may omit optional fields. Present, typed fields cannot change shape silently. */
 public static ObjectNode object(Stage stage,String raw){
  var value=unwrap(stage,(ObjectNode)ModelOutput.object(raw),0);
  // Recover the unambiguous collection alias without changing the saved author's reply.
  if(stage==Stage.STORY_FRAME && !value.has("scenes") && value.path("scene").isArray()){
   value.set("scenes",value.remove("scene"));
  }
  shape(stage,value);return value;
 }
 private static final Set<String> ENVELOPE_FIELDS=Set.of("type","properties","data","result","response","output","payload","status","success","message","code","protocolVersion","version","stage","$schema","required","additionalProperties");
 private static final List<String> ENVELOPE_KEYS=List.of("properties","data","result","response","output","payload");
 private static final Set<String> SCHEMA_FIELDS=Set.of("$schema","$id","$ref","$defs","definitions","type","properties","items","required","additionalProperties","anyOf","oneOf","allOf","not","const","enum","description","title","default","examples","minimum","maximum","exclusiveMinimum","exclusiveMaximum","minLength","maxLength","minItems","maxItems","minProperties","maxProperties","uniqueItems","pattern","format","nullable","readOnly","writeOnly","deprecated","contentMediaType");
 /** Recover only an unambiguous transport wrapper. The author's values remain literal;
  * actual stage fields at the root always win over a nested alternative. */
 private static ObjectNode unwrap(Stage stage,ObjectNode value,int depth){
  var fields=SCHEMAS.get(stage).path("properties");
  if(fields.isEmpty())return value;
  for(var field:fields.properties())if(value.has(field.getKey()))return value;
  boolean envelope=true;for(var key:value.properties())if(!ENVELOPE_FIELDS.contains(key.getKey())){envelope=false;break;}
  ObjectNode accepted=null;
  if(envelope && depth<4)for(String key:ENVELOPE_KEYS)if(value.path(key).isObject()){
   var candidate=unwrap(stage,(ObjectNode)value.path(key),depth+1);
   if(!hasInstanceField(fields,candidate))continue;
   if(accepted!=null && !accepted.equals(candidate))throw new IllegalArgumentException("模型交接格式 "+stage+" 存在互相矛盾的数据外壳，未猜选其中一份；原回复保留");
   accepted=candidate;
  }
  if(accepted!=null)return accepted.deepCopy();
  if(value.has("properties") && schemaNode(value))throw new IllegalArgumentException("模型交接格式 "+stage+" 返回的是字段定义而非实际数据；原回复保留");
  return value;
 }
 private static boolean hasInstanceField(JsonNode fields,ObjectNode value){
  for(var field:fields.properties()){
   var actual=value.path(field.getKey());if(actual.isMissingNode() || actual.isNull())continue;
   String type=field.getValue().path("type").asText();
   boolean same=switch(type){case "string"->actual.isTextual();case "array"->actual.isArray();case "object"->actual.isObject();case "number"->actual.isNumber();case "boolean"->actual.isBoolean();default->true;};
   if(same && !schemaNode(actual))return true;
  }return false;
 }
 /** A real JSON Schema contains schema keywords, not year/day/period or story values. */
 private static boolean schemaNode(JsonNode value){
  if(value.isBoolean())return true; // JSON Schema permits boolean schemas.
  if(!value.isObject())return false;
  for(var field:value.properties())if(!SCHEMA_FIELDS.contains(field.getKey()))return false;
  if(value.has("type")){
   var type=value.path("type");var names=Set.of("object","array","string","number","integer","boolean","null");
   if(type.isTextual()){if(!names.contains(type.asText()))return false;}
   else if(type.isArray()){for(var name:type)if(!name.isTextual() || !names.contains(name.asText()))return false;}
   else return false;
  }
  if(value.path("properties").isObject())for(var property:value.path("properties"))if(!schemaNode(property))return false;
  if(value.has("items") && !schemaNode(value.path("items")))return false;
  return true;
 }
 public static void shape(Stage stage,ObjectNode value){check(schema(stage),value,"$",stage);}
 private static void check(JsonNode schema,JsonNode value,String path,Stage stage){
  if(schema.path("x-presentation-only").asBoolean(false))return;
  if(value.isNull())return; // Legacy optional unknowns are left for the field's existing reader.
  String type=schema.path("type").asText();boolean ok=switch(type){
   case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->true;
  };
  if(!ok && "object".equals(schema.path("x-legacy-type").asText()) && value.isObject())return;
  if(!ok && "string".equals(schema.path("x-legacy-type").asText()) && value.isTextual())return;
  if(!ok)throw new IllegalArgumentException("模型交接格式 "+stage+" "+path+" 应为"+type+"；原回复保留，只恢复此阶段");
  if(value.isObject())for(var field:schema.path("properties").properties())if(value.has(field.getKey()))check(field.getValue(),value.path(field.getKey()),path+"."+field.getKey(),stage);
  if(value.isArray() && schema.has("items"))for(int i=0;i<value.size();i++)check(schema.path("items"),value.get(i),path+"["+i+"]",stage);
  if(value.isObject() && schema.path("additionalProperties").isObject())for(var field:value.properties())check(schema.path("additionalProperties"),field.getValue(),path+"."+field.getKey(),stage);
 }
}
