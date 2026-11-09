package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;

/** One request per explicit attempt; a retry repairs the saved candidate, never redraws a valid opening. */
@Service
public class CreationModelExchange {
 private final ModelReplyJournal journal;private final ObjectMapper json=new ObjectMapper();
 public CreationModelExchange(ModelReplyJournal journal){this.journal=journal;}
 public LLMResponse request(String sid,ModelProtocol.Stage stage,List<Map<String,String>> input,Function<List<Map<String,String>>,LLMResponse> call){
  return request(sid,stage,input,call,value->{});
 }
 public LLMResponse request(String sid,ModelProtocol.Stage stage,List<Map<String,String>> input,Function<List<Map<String,String>>,LLMResponse> call,java.util.function.Consumer<ObjectNode> fields){
  var messages=ModelProtocol.messages(stage,input);
  // A protocol/parser upgrade does not authorize drawing a new world or birth.
  // Identity follows this session, stage and actual supplied creation data.
  var actualInput=input.stream().filter(message->!"system".equals(message.get("role"))).toList();
  String legacySource=StoryArchiveService.source(json.valueToTree(actualInput));
  var identity=json.createArrayNode();
  for(var message:actualInput){
   var entry=json.valueToTree(message);String content=message.get("content");
   if(content!=null && (content.stripLeading().startsWith("{") || content.stripLeading().startsWith("["))){
    try{((ObjectNode)entry).set("content",json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content));}
    catch(java.io.IOException plainText){/* Text stays literal; identity never guesses malformed input. */}
   }
   identity.add(entry);
  }
  String canonicalSource=StoryArchiveService.source(identity);
  var saved=journal.find(sid,stage,canonicalSource);String selectedSource=canonicalSource;
  if(saved==null && !canonicalSource.equals(legacySource)){saved=journal.find(sid,stage,legacySource);if(saved!=null)selectedSource=legacySource;}
  final String source=selectedSource;LLMResponse reply;LLMResponse original;
  if(saved!=null){
   try{
    // A syntactically valid transport fragment still needs completion. Parser
    // upgrades may recover complete replies, but cannot finish a truncated one.
    if(saved.incomplete())throw new IllegalArgumentException("上一回复尚未完整结束："+saved.issue());
    var accepted=accepted(stage,saved.latest(),saved.original(),fields);if(!saved.issue().isBlank())journal.issue(sid,stage,source,"");return accepted;
   }catch(IllegalArgumentException unreadable){
    var repair=new ArrayList<>(messages);repair.add(Map.of("role","assistant","content",Objects.toString(saved.latest().content(),"")));
    repair.add(Map.of("role","user","content","上一份是本阶段尚未发表的候选回复，已完整保留，不能当成已发生历史。只修复同一候选的JSON结构、字段类型和缺失字段，不重抽世界、人物或故事，不改变已经完整生成的正文字符串。保留其中已有内容；若上次在字段中截断，接完这份候选。具体读取问题："+unreadable.getMessage()));
    reply=invoke(sid,stage,source,repair,call,true);original=saved.original();
   }
  }else{reply=invoke(sid,stage,source,messages,call,false);original=reply;}
  try{var result=accepted(stage,reply,original,fields);journal.issue(sid,stage,source,"");return result;}
  catch(IllegalArgumentException invalid){journal.issue(sid,stage,source,invalid.getMessage());throw new ModelReplyFormatException(stage,"本阶段格式尚未完成，原回复已保存；重试仅修复此阶段："+invalid.getMessage(),reply.content(),invalid);}
 }
 private LLMResponse invoke(String sid,ModelProtocol.Stage stage,String source,List<Map<String,String>> messages,Function<List<Map<String,String>>,LLMResponse> call,boolean repair){
  try{var reply=call.apply(messages);journal.received(sid,stage,source,reply,repair);return reply;}
  catch(LLMApiException failed){if(!failed.partialResponse().isBlank()){
   var retained=LLMResponse.raw(failed.partialResponse(),0,0,0);
   if("FORMAT".equals(failed.finishReason()))journal.received(sid,stage,source,retained,repair);
   else journal.receivedIncomplete(sid,stage,source,retained,repair,failed.category()+"；费用以服务商用量为准");
  }throw failed;}
 }
 private LLMResponse accepted(ModelProtocol.Stage stage,LLMResponse reply,LLMResponse original,java.util.function.Consumer<ObjectNode> fields){
  String raw=reply.parsedJson()!=null?reply.parsedJson():reply.content();String extracted=LLMResponseParser.extractJsonOrNull(raw);
  var value=ModelProtocol.object(stage,extracted!=null?extracted:raw);
  // Preserve completed author text, not the empty/invalid values the repair must fill.
  if(stage==ModelProtocol.Stage.BIRTH || stage==ModelProtocol.Stage.WORLD)for(var entry:completedRootStrings(stage,original.content()).entrySet())
   if(preservableString(stage,entry.getKey(),entry.getValue()))value.put(entry.getKey(),entry.getValue());
  if(stage==ModelProtocol.Stage.WORLD)WorldSettingService.normalize(value);
  if(stage==ModelProtocol.Stage.TALENTS){
   var names=new HashSet<String>();for(var talent:value.path("talents"))if(!talent.path("talent_name").asText().isBlank())names.add(talent.path("talent_name").asText());
   if(names.size()<3)throw new IllegalArgumentException("talents需要至少3个由主API生成的不同名称候选，不用固定天赋补齐");
  }
  if(stage==ModelProtocol.Stage.BIRTH && value.path("birth_story").asText().isBlank())throw new IllegalArgumentException("birth_story需要完整正文");
  if(stage==ModelProtocol.Stage.BIRTH && value.hasNonNull("initial_moment"))StoryClockService.moment(value.path("initial_moment"));
  if(stage==ModelProtocol.Stage.OPENING_ITEMS && !value.hasNonNull("initial_inventory"))throw new IllegalArgumentException("initial_inventory需要物品登记数组");
  fields.accept(value);
  return new LLMResponse(reply.content(),value.toString(),reply.tokenInput(),reply.tokenOutput(),reply.tokenTotal(),reply.costCny());
 }
 private boolean preservableString(ModelProtocol.Stage stage,String field,String value){
  if(value.isBlank() || !ModelProtocol.schema(stage).at("/properties/"+field+"/type").asText().equals("string"))return false;
  return stage!=ModelProtocol.Stage.WORLD || !field.equals("culturalStyle") || Set.of("EASTERN","WESTERN","MODERN","SCIFI","MIXED").contains(value.trim().toUpperCase(Locale.ROOT));
 }
 private static final Set<String> STRING_ENVELOPE_FIELDS=Set.of("type","properties","data","result","response","output","payload","status","success","message","code","protocolVersion","version","stage","$schema","required","additionalProperties");
 private static final Set<String> STRING_ENVELOPE_KEYS=Set.of("properties","data","result","response","output","payload");
 private static final class CompletedObject {
  final int depth;final Set<String> fields=new HashSet<>(),conflicting=new HashSet<>();final Map<String,String> strings=new LinkedHashMap<>();final List<CompletedObject> children=new ArrayList<>();
  CompletedObject(int depth){this.depth=depth;}
 }
 private Map<String,String> completedRootStrings(ModelProtocol.Stage stage,String raw){
  if(raw==null)return Map.of();String text=raw.stripLeading();
  if(!text.startsWith("{") && !text.startsWith("[")){
   String extracted=LLMResponseParser.extractJsonOrNull(text);
   if(extracted!=null)text=extracted;
   else if(text.startsWith("```")){int line=text.indexOf('\n');if(line>=0)text=text.substring(line+1);}
  }
  CompletedObject root=null;var objects=new IdentityHashMap<JsonStreamContext,CompletedObject>();
  try(var parser=json.createParser(text)){
   while(parser.nextToken()!=null){var context=parser.getParsingContext();
    if(parser.currentToken()==JsonToken.START_OBJECT){
     objects.remove(context); // Jackson may reuse a sibling's parsing context.
     var parent=objects.get(context.getParent());
     if(context.getParent().inRoot()){if(root!=null)return Map.of();root=new CompletedObject(0);objects.put(context,root);}
     else if(parent!=null && parent.depth<4 && STRING_ENVELOPE_KEYS.contains(context.getParent().getCurrentName())){var child=new CompletedObject(parent.depth+1);parent.children.add(child);objects.put(context,child);}
    }else if(parser.currentToken()==JsonToken.START_ARRAY)objects.remove(context);
    else if(parser.currentToken()==JsonToken.FIELD_NAME){var object=objects.get(context);if(object!=null)object.fields.add(parser.currentName());}
    else if(parser.currentToken()==JsonToken.VALUE_STRING){var object=objects.get(context);if(object!=null){String field=parser.currentName(),value=parser.getText();if(object.strings.containsKey(field) && !object.strings.get(field).equals(value))object.conflicting.add(field);else object.strings.put(field,value);}}
   }
  }catch(java.io.IOException incomplete){/* Preserve closed strings before truncation, including inside explicit transport envelopes. */}
  try{var values=completedStrings(stage,root);return values==null?Map.of():values;}
  catch(IllegalArgumentException ambiguous){return Map.of();/* A repair must disambiguate competing originals; do not pin either one. */}
 }
 private Map<String,String> completedStrings(ModelProtocol.Stage stage,CompletedObject value){
  if(value==null)return null;var schema=ModelProtocol.schema(stage).path("properties");
  if(value.fields.stream().anyMatch(schema::has)){var out=new LinkedHashMap<>(value.strings);value.conflicting.forEach(out::remove);return out;}
  if(!STRING_ENVELOPE_FIELDS.containsAll(value.fields))return null;
  Map<String,String> accepted=null;for(var child:value.children){var candidate=completedStrings(stage,child);if(candidate==null)continue;if(accepted!=null && !accepted.equals(candidate))throw new IllegalArgumentException("Conflicting original envelopes");accepted=candidate;}
  return accepted;
 }
}
