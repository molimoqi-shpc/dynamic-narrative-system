package com.bs.narrative.service;
import com.bs.narrative.domain.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.*;

/** Inventory remains authoritative. Each unit has a stable reference and a truthful provenance trail. */
@Service @Transactional
public class ItemStateService {
 private final JdbcTemplate jdbc;private static final ObjectMapper JSON=new ObjectMapper();
 public ItemStateService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public record Item(String ref,String name,String ownerId,String origin,List<Map<String,String>> history){}
 public record Craft(String actorId,List<Item> materials,List<Item> tools,Item output,String basis){}
 public record Plan(Map<String,Item> refs,List<Craft> crafts){}
 public record ObservedUse(String sourceActorId,String label,String itemName){}
 /** Observation evidence comes only from another participant's verified execution in this batch. */
 public List<ObservedUse> observedUses(Map<String,GodProfile> actors,JsonNode result,Plan plan,String observer){
  var evidence=new ArrayList<ObservedUse>();
  for(var event:result.path("events")){
   String source=event.path("actorId").asText();var owner=actors.get(source);
   if(owner==null || source.equals(observer) || event.path("text").asText().isBlank())continue;
   String name=owner.getGodName();var labels=new LinkedHashSet<String>();labels.add(name);
   if(name.matches("[\\p{IsHan}]+"))for(int length=2;length<=Math.min(4,name.length());length++){
    String alias=name.substring(name.length()-length);
    if(actors.values().stream().filter(a->a.getGodName().endsWith(alias)).count()==1)labels.add(alias);
   }
   for(var item:select(event.path("uses"),plan.refs(),source)){
    String actual=event.path("text").asText();
    if(!actual.contains(item.name()) && !actual.contains("[[ITEM:"+item.ref()+"]]"))continue;
    for(String label:labels)evidence.add(new ObservedUse(source,label,item.name()));
   }
  }
  return List.copyOf(evidence);
 }
 private List<String> inventory(GodProfile g){return g.getInventory()==null?List.of():g.getInventory();}
 private void store(String sid,Item item,String state){store(sid,item,state,"");}
 private void store(String sid,Item item,String state,String scene){try{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'物品来源与持有',?,'ITEM_STATE','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id)",sid,item.ownerId(),item.ref(),JSON.writeValueAsString(Map.of("ref",item.ref(),"name",item.name(),"ownerId",item.ownerId(),"holderId","HELD".equals(state)?item.ownerId():"","legalOwnerId",legalOwner(item),"sceneId",scene,"origin",item.origin(),"state",state,"history",item.history())),scene);
 }catch(Exception e){throw new IllegalStateException("物品记录无法保存",e);}}
 private Item decode(String raw){try{var n=JSON.readTree(raw);var history=new ArrayList<Map<String,String>>();for(var h:n.path("history"))history.add(JSON.convertValue(h,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){}));return new Item(n.path("ref").asText(),n.path("name").asText(),n.path("ownerId").asText(),n.path("origin").asText(),history);}catch(Exception e){throw new IllegalStateException("物品记录损坏",e);}}
 public List<Item> owned(String sid,GodProfile actor){
  if(!sid.equals(actor.getSessionId()))throw new IllegalArgumentException("foreign item owner");
  jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_id=? FOR UPDATE",sid,actor.getGodId());
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE' ORDER BY knowledge_id",String.class,sid,actor.getGodId());
  var held=new ArrayList<Item>();for(String raw:rows)try{if("HELD".equals(JSON.readTree(raw).path("state").asText()))held.add(decode(raw));}catch(Exception e){throw new IllegalStateException(e);}
  var result=new ArrayList<Item>();var used=new HashSet<String>();
  for(String name:inventory(actor)){
   if(name==null || name.isBlank())throw new IllegalArgumentException("invalid inventory name");
   var existing=held.stream().filter(i->name.equals(i.name()) && !used.contains(i.ref())).findFirst();
   Item item=existing.orElseGet(()->new Item("item_"+UUID.randomUUID().toString().replace("-",""),name,actor.getGodId(),"原已持有，历史来源未记录",List.of(Map.of("kind","EXISTING_INVENTORY","ownerId",actor.getGodId()))));
   if(existing.isEmpty())store(sid,item,"HELD");used.add(item.ref());result.add(item);
  }
  for(var item:held)if(!used.contains(item.ref()))store(sid,item,"REMOVED_BY_ENGINE");
  return List.copyOf(result);
 }
 /** Only a new character's accepted genesis packet may establish this provenance. */
 public void establishInitialInventory(String sid,GodProfile actor){
  if(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE'",Integer.class,sid,actor.getGodId())>0)throw new IllegalArgumentException("existing provenance cannot be rewritten as genesis");
  for(var item:owned(sid,actor))store(sid,new Item(item.ref(),item.name(),actor.getGodId(),"场景建立时已明确持有的初始资源",List.of(Map.of("kind","GENESIS","ownerId",actor.getGodId(),"legalOwnerId",actor.getGodId()))),"HELD");
 }
 public String ownContext(String sid,GodProfile actor){return "【自己的物品来源与编号】"+owned(sid,actor).stream().map(i->Map.of("ref",i.ref(),"name",i.name(),"ownerId",i.ownerId())).toList()+"。只有当前持有者能使用；物品提议、别人的说法和过去库存不等于当前持有。\n";}
 /** Carried objects follow their holder; hand/basket/foot-level descriptions are prose, not inventory states. */
 public List<Map<String,Object>> custody(String sid,GodProfile actor){
  return owned(sid,actor).stream().map(i->(Map<String,Object>)Map.<String,Object>of("ref",i.ref(),"name",i.name(),"holderId",actor.getGodId(),"legalOwnerId",legalOwner(i),"origin",i.origin())).toList();
 }
 public List<Map<String,Object>> sceneItems(String sid,String scene){
  var result=new ArrayList<Map<String,Object>>();
  for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND scene_id=? AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.state'))='IN_SCENE' ORDER BY knowledge_id",String.class,sid,scene)){
   var item=decode(raw);result.add(Map.of("ref",item.ref(),"name",item.name(),"holderId","","legalOwnerId",legalOwner(item),"sceneId",scene));
  }return result;
 }
 public Item leaveInScene(String sid,GodProfile holder,String scene,String ref){
  if(!sid.equals(holder.getSessionId()) || jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND scene_id=?",Integer.class,sid,scene)!=1)throw new IllegalArgumentException("foreign item scene");
  var item=owned(sid,holder).stream().filter(i->ref.equals(i.ref())).findFirst().orElseThrow(()->new IllegalArgumentException("item no longer held"));
  var inventory=new ArrayList<>(inventory(holder));if(!inventory.remove(item.name()))throw new IllegalArgumentException("inventory changed");
  var history=new ArrayList<>(item.history());history.add(Map.of("kind","DROP","sceneId",scene,"legalOwnerId",legalOwner(item)));
  store(sid,new Item(item.ref(),item.name(),holder.getGodId(),item.origin(),List.copyOf(history)),"IN_SCENE",scene);holder.setInventory(inventory);return item;
 }
 public Item takeFromScene(String sid,GodProfile taker,String scene,String ref){
  if(!sid.equals(taker.getSessionId()))throw new IllegalArgumentException("foreign item holder");
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND scene_id=? AND source_ref=? AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.state'))='IN_SCENE' FOR UPDATE",String.class,sid,scene,ref);
  if(rows.size()!=1)throw new IllegalArgumentException("item is no longer in this scene");
  var item=decode(rows.get(0));store(sid,item,"TAKEN_FROM_SCENE");
  var history=new ArrayList<>(item.history());history.add(Map.of("kind","PICKUP","sceneId",scene,"from",item.ownerId(),"to",taker.getGodId(),"legalOwnerId",legalOwner(item)));
  var acquired=new Item(item.ref(),item.name(),taker.getGodId(),item.origin(),List.copyOf(history));store(sid,acquired,"HELD");
  var inventory=new ArrayList<>(inventory(taker));inventory.add(item.name());taker.setInventory(inventory);return acquired;
 }
 public List<Map<String,Object>> describeOwned(String sid,GodProfile actor){
  if(!sid.equals(actor.getSessionId()))throw new IllegalArgumentException("foreign inventory");
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE' ORDER BY knowledge_id",String.class,sid,actor.getGodId());
  var held=new ArrayList<Item>();for(String raw:rows)try{if("HELD".equals(JSON.readTree(raw).path("state").asText()))held.add(decode(raw));}catch(Exception e){throw new IllegalStateException(e);}
  var result=new ArrayList<Map<String,Object>>();var used=new HashSet<String>();int index=0;
  for(String name:inventory(actor)){var item=held.stream().filter(i->name.equals(i.name()) && !used.contains(i.ref())).findFirst();
   String ref=item.map(Item::ref).orElse("legacy_"+index++),source=item.map(Item::origin).orElse("原已持有，历史来源未记录");
   if(item.isPresent()){used.add(ref);var history=item.get().history();if(!history.isEmpty())source=switch(Objects.toString(history.get(history.size()-1).get("kind"),"")){
    case "TRANSFER" -> "实际转赠或交换取得";
    case "GIFT" -> "实际赠予取得";
    case "RETURN" -> "借出后已实际收回";
    case "HOLD" -> "实际转交持有，归属方式未另作约定";
    default -> source;
   };}
   String owner=item.map(ItemStateService::legalOwner).orElse(actor.getGodId());
   String ownerName=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,owner).stream().findFirst().orElse("原所有者");
   String custody="";
   if(item.isPresent()){var history=item.get().history();for(int h=history.size()-1;h>=0;h--){String kind=Objects.toString(history.get(h).get("kind"),"");if(Set.of("SEIZE","LEND","RETURN","GIFT","HOLD","TRANSFER","GENESIS","EXISTING_INVENTORY").contains(kind)){custody=kind;break;}}}
   boolean seized="SEIZE".equals(custody) && !actor.getGodId().equals(owner);
   boolean borrowed=!actor.getGodId().equals(owner) && !seized && !"HOLD".equals(custody);
   result.add(Map.of("ref",ref,"name",name,"ownerName",ownerName,"holderName",actor.getGodName(),"isBorrowed",borrowed,"isSeized",seized,"source",seized?"冲突中实际夺取持有":borrowed?"实际借用，原所有者保留归还关系":source));
  }return result;
 }
 public void exchange(String sid,GodProfile giver,GodProfile recipient,String offered,String requested){
  if(giver.getGodId().equals(recipient.getGodId()))throw new IllegalArgumentException("self transfer");
  var from=owned(sid,giver);var to=owned(sid,recipient);
  Item a=from.stream().filter(i->i.name().equals(offered)).findFirst().orElseThrow(()->new IllegalArgumentException("missing offered item"));
  Item b=requested==null || requested.isBlank()?null:to.stream().filter(i->i.name().equals(requested)).findFirst().orElseThrow(()->new IllegalArgumentException("missing requested item"));
  move(sid,a,recipient.getGodId());if(b!=null)move(sid,b,giver.getGodId());
 }
 private void move(String sid,Item item,String recipient){
  store(sid,item,"TRANSFERRED");var history=new ArrayList<>(item.history());history.add(Map.of("kind","TRANSFER","from",item.ownerId(),"to",recipient));
  store(sid,new Item(item.ref(),item.name(),recipient,item.origin(),List.copyOf(history)),"HELD");
 }
 /** Exact-unit delivery. Holder changes independently of the legal owner on a loan. */
 public void transferExact(String sid,GodProfile giver,GodProfile recipient,String ref,String mode){
  transferExact(sid,giver,recipient,ref,mode,true);
 }
 public void transferNarrative(String sid,GodProfile giver,GodProfile recipient,String ref,String mode){
  transferExact(sid,giver,recipient,ref,mode,false);
 }
 private void transferExact(String sid,GodProfile giver,GodProfile recipient,String ref,String mode,boolean enforceOwnership){
  if(giver.getGodId().equals(recipient.getGodId()) || !(Set.of("GIFT","LEND","RETURN").contains(mode) || !enforceOwnership && Set.of("SEIZE","HOLD").contains(mode)))throw new IllegalArgumentException("invalid transfer");
  Item item=owned(sid,giver).stream().filter(i->ref.equals(i.ref())).findFirst().orElseThrow(()->new IllegalArgumentException("item no longer held"));
  String legalOwner=legalOwner(item);
  if(enforceOwnership && ("LEND".equals(mode) && !legalOwner.equals(giver.getGodId()) || "GIFT".equals(mode) && !legalOwner.equals(giver.getGodId())
     || "RETURN".equals(mode) && (!legalOwner.equals(recipient.getGodId()) || legalOwner.equals(giver.getGodId()))))throw new IllegalArgumentException("holder cannot transfer this ownership");
  var from=new ArrayList<>(inventory(giver));var to=new ArrayList<>(inventory(recipient));
  if(!from.remove(item.name()))throw new IllegalArgumentException("inventory changed");to.add(item.name());
  var history=new ArrayList<>(item.history());history.add(Map.of("kind",mode,"from",giver.getGodId(),"to",recipient.getGodId(),"legalOwnerId","GIFT".equals(mode)?recipient.getGodId():legalOwner));
  store(sid,item,"TRANSFERRED");store(sid,new Item(item.ref(),item.name(),recipient.getGodId(),item.origin(),List.copyOf(history)),"HELD");
  giver.setInventory(from);recipient.setInventory(to);
 }
 public static String legalOwner(Item item){
  for(int n=item.history().size()-1;n>=0;n--){var event=item.history().get(n);if(event.containsKey("legalOwnerId"))return event.get("legalOwnerId");if("TRANSFER".equals(event.get("kind")))return event.get("to");}
  return item.ownerId();
 }
 /** The scene resource service supplies the verified, finite source, never the model. */
 public Item acquireGathered(String sid,GodProfile actor,String name,String scene,String sourceRef,String source){
  if(!sid.equals(actor.getSessionId()))throw new IllegalArgumentException("foreign item owner");
  var item=new Item("item_"+UUID.randomUUID().toString().replace("-",""),name,actor.getGodId(),"在"+scene+"实际采集："+source,
   List.of(Map.of("kind","GATHER","sourceRef",sourceRef,"source",source,"legalOwnerId",actor.getGodId())));
  var next=new ArrayList<>(inventory(actor));next.add(name);store(sid,item,"HELD");actor.setInventory(next);return item;
 }
 public Item acquireNarrative(String sid,GodProfile actor,String name,String source){
  if(!sid.equals(actor.getSessionId()))throw new IllegalArgumentException("foreign item owner");
  var item=new Item("item_"+UUID.randomUUID().toString().replace("-",""),name,actor.getGodId(),source,List.of(Map.of("kind","STORY_GAIN","source",source,"legalOwnerId",actor.getGodId())));
  var next=new ArrayList<>(inventory(actor));next.add(name);store(sid,item,"HELD");actor.setInventory(next);return item;
 }
 public void consumeExact(String sid,GodProfile actor,String ref,String purpose){
  consumeExact(sid,actor,ref,purpose,true);
 }
 public void consumeNarrative(String sid,GodProfile actor,String ref){consumeExact(sid,actor,ref,"STORY_USE_UP",false);}
 private void consumeExact(String sid,GodProfile actor,String ref,String purpose,boolean enforceOwnership){
  var item=owned(sid,actor).stream().filter(i->ref.equals(i.ref())).findFirst().orElseThrow(()->new IllegalArgumentException("consumed item no longer held"));
  if(enforceOwnership && !actor.getGodId().equals(legalOwner(item)))throw new IllegalArgumentException("borrowed item cannot be consumed");
  var next=new ArrayList<>(inventory(actor));if(!next.remove(item.name()))throw new IllegalArgumentException("inventory changed");
  var history=new ArrayList<>(item.history());history.add(Map.of("kind","CONSUME","purpose",purpose,"legalOwnerId",legalOwner(item)));
  store(sid,new Item(item.ref(),item.name(),item.ownerId(),item.origin(),List.copyOf(history)),"CONSUMED");actor.setInventory(next);
 }
 /** Adapt an ordinary operation effect to the existing material/provenance validator. */
 public Plan prepareOrdinaryCraft(String sid,GodProfile actor,JsonNode effect,String process,JsonNode uses){
  String name=effect.path("name").asText(),basis=effect.path("basis").asText();
  if(!effect.path("name").isTextual() || name.isBlank() || name.contains("[[") || name.contains("]]")
     || !effect.path("basis").isTextual() || basis.isBlank() || !process.contains(basis) || !basis.contains(name)
     || basis.contains("[[") || basis.contains("]]"))throw new IllegalArgumentException("craft needs an exact completed output basis");
  var held=new HashMap<String,Item>();for(var item:owned(sid,actor))held.put(item.ref(),item);
  var declared=new HashSet<String>();for(var use:uses)if(use.isTextual())declared.add(use.asText());
  for(String field:List.of("materials","tools")){
   if(!effect.path(field).isArray())throw new IllegalArgumentException("craft needs material and tool reference arrays");
   for(var ref:effect.path(field)){
    var item=ref.isTextual()?held.get(ref.asText()):null;
    if(item==null || !declared.contains(item.ref()))throw new IllegalArgumentException("craft input must be held and declared in uses");
    if("materials".equals(field) && !actor.getGodId().equals(legalOwner(item)))throw new IllegalArgumentException("borrowed material cannot be consumed by crafting");
   }
  }
  // The legacy protocol grounds output by a local marker. Only add it after validating the original exact excerpt.
  String marked=basis.replace(name,"[[ITEM:crafted_0]]");var packet=JSON.createObjectNode();
  var craft=packet.putArray("crafts").addObject().put("actorId",actor.getGodId()).put("name",name).put("ref","crafted_0").put("basis",marked);
  craft.set("materials",effect.path("materials").deepCopy());craft.set("tools",effect.path("tools").deepCopy());
  var event=packet.putArray("events").addObject().put("actorId",actor.getGodId()).put("text",process.replace(basis,marked));
  var references=event.putArray("uses");references.add("crafted_0");for(String field:List.of("materials","tools"))for(var ref:effect.path(field))references.add(ref.asText());
  return prepare(sid,Map.of(actor.getGodId(),actor),packet);
 }
 @Transactional(noRollbackFor=IllegalArgumentException.class)
 public Plan prepare(String sid,Map<String,GodProfile> actors,JsonNode response){
  var refs=new LinkedHashMap<String,Item>();for(var actor:actors.values())for(var i:owned(sid,actor))refs.put(i.ref(),i);
  var crafts=new ArrayList<Craft>();var reserved=new HashSet<String>();var retainedTools=new HashSet<String>();var nodes=response.path("crafts");
  if(!nodes.isMissingNode() && !nodes.isArray())throw new IllegalArgumentException("invalid crafts array");
  for(var n:nodes){if(crafts.size()>=4)throw new IllegalArgumentException("too many crafts");
   String actor=n.path("actorId").asText(),name=n.path("name").asText(),ref=n.path("ref").asText(),basis=n.path("basis").asText();
   // Identity and resource accounting use exact references; names need not be unique.
   if(!actors.containsKey(actor) || name.isBlank() || name.length()>64 || !ref.matches("crafted_[0-9]{1,2}") || refs.containsKey(ref))throw new IllegalArgumentException("invalid craft output");
   if(com.bs.narrative.engine.InventoryMatcher.TALISMAN_KEYWORDS.stream().anyMatch(name::contains))throw new IllegalArgumentException("ordinary crafting cannot create rule-managed magical items");
   var materials=select(n.path("materials"),refs,actor);var tools=select(n.path("tools"),refs,actor);
   if(materials.isEmpty() || materials.size()>8)throw new IllegalArgumentException("craft needs owned materials");
   for(var material:materials){
    if(!actor.equals(legalOwner(material)))throw new IllegalArgumentException("borrowed material cannot be consumed by crafting");
    if(retainedTools.contains(material.ref()) || !reserved.add(material.ref()))throw new IllegalArgumentException("material used twice");
   }
   for(var tool:tools){if(reserved.contains(tool.ref()))throw new IllegalArgumentException("consumed material cannot also be a tool");retainedTools.add(tool.ref());}
   for(var input:n.path("materials"))if(input.asText().startsWith("crafted_"))throw new IllegalArgumentException("craft inputs must already be held before this action");
   for(var input:n.path("tools"))if(input.asText().startsWith("crafted_"))throw new IllegalArgumentException("craft tools must already be held before this action");
   boolean grounded=false;String proofIssue="missing event for the crafting actor";for(var e:response.path("events"))if(actor.equals(e.path("actorId").asText())){
    var used=new HashSet<String>();for(var use:e.path("uses"))used.add(use.asText());
    boolean declared=used.contains(ref) && materials.stream().allMatch(i->used.contains(i.ref())) && tools.stream().allMatch(i->used.contains(i.ref()));
    String text=e.path("text").asText();
    String marker="[[ITEM:"+ref+"]]";
    if(!declared)proofIssue="event.uses must include output ref, all material refs and tool refs";
    else if(!basis.contains(marker))proofIssue="craft.basis must include output marker "+marker;
    else if(basis.isBlank() || !text.contains(basis))proofIssue="craft.basis must be an exact continuous excerpt of this actor event.text";
    else if(!OpenSceneService.positiveEvidence(List.of(text),basis))proofIssue="craft.basis describes a plan, negation or unconfirmed result; incomplete work must not create an item";
    else if(!basis.matches("(?s).*(制成|做成|削成|磨成|加工成|拼成|缝成|编成|制作完成|完成制作|完成加工).*"))proofIssue="craft.basis must explicitly state completed processing with the output marker";
    else grounded=true;
   }
   if(!grounded)throw new IllegalArgumentException("craft has no actual output event: "+proofIssue);
   var history=List.of(Map.of("kind","CRAFT","materials",String.join(",",materials.stream().map(Item::ref).toList()),"tools",String.join(",",tools.stream().map(Item::ref).toList())));
   var output=new Item("item_"+UUID.randomUUID().toString().replace("-",""),name,actor,"由实际持有材料制作",history);refs.put(ref,output);crafts.add(new Craft(actor,materials,tools,output,basis));
  }
  for(var e:response.path("events")){
   String actor=e.path("actorId").asText();var uses=e.path("uses");var declared=select(uses,refs,actor);Set<String> declaredRefs=new HashSet<>();for(var use:uses)declaredRefs.add(use.asText());
   var markers=Pattern.compile("\\[\\[ITEM:([^]]+)]]").matcher(e.path("text").asText());while(markers.find())if(!declaredRefs.contains(markers.group(1)))throw new IllegalArgumentException("undeclared item reference");
   // Tools cannot be borrowed merely by naming another owner in a model answer.
   for(var use:declared)if(!actor.equals(use.ownerId()))throw new IllegalArgumentException("foreign tool owner");
  }
  return new Plan(Map.copyOf(refs),List.copyOf(crafts));
 }
 private List<Item> select(JsonNode node,Map<String,Item> refs,String owner){
  if(node.isMissingNode())return List.of();if(!node.isArray() || node.size()>12)throw new IllegalArgumentException("invalid item references");
  var selected=new ArrayList<Item>();var seen=new HashSet<String>();for(var reference:node){
   // Only the reference selects a server-owned item. Copied name/owner metadata is never authority.
   String ref=reference.isTextual()?reference.asText():reference.isObject() && reference.path("ref").isTextual()?reference.path("ref").asText():"";
   var item=refs.get(ref);if(item==null || !owner.equals(item.ownerId()) || !seen.add(ref))throw new IllegalArgumentException("item missing, duplicated, or belongs to another actor: ref="+ref+", actor="+owner);selected.add(item);
  }return selected;
 }
 public void apply(String sid,Plan plan,Map<String,GodProfile> actors){
  var next=new HashMap<String,List<String>>();for(var craft:plan.crafts()){
   var inv=next.computeIfAbsent(craft.actorId(),id->new ArrayList<>(inventory(actors.get(id))));
   for(var material:craft.materials())if(!inv.remove(material.name()))throw new IllegalArgumentException("material no longer owned");inv.add(craft.output().name());
  }
  for(var craft:plan.crafts()){for(var material:craft.materials())store(sid,material,"CONSUMED");store(sid,craft.output(),"HELD");}
  next.forEach((id,inventory)->actors.get(id).setInventory(inventory));
 }
 public JsonNode render(JsonNode n,Plan plan){
  if(n.isTextual())return com.fasterxml.jackson.databind.node.TextNode.valueOf(renderText(n.asText(),plan.refs()));
  if(n.isObject()){var result=JSON.createObjectNode();n.fields().forEachRemaining(e->result.set(e.getKey(),render(e.getValue(),plan)));return result;}
  if(n.isArray()){var result=JSON.createArrayNode();n.forEach(e->result.add(render(e,plan)));return result;}return n;
 }
 private String renderText(String text,Map<String,Item> refs){
  for(var entry:refs.entrySet()){String marker="[[ITEM:"+entry.getKey()+"]]",name=entry.getValue().name();text=text.replace(name+marker,marker).replace(marker+name,marker);}
  var m=Pattern.compile("\\[\\[ITEM:([^]]+)]]").matcher(text);var out=new StringBuffer();while(m.find()){var item=refs.get(m.group(1));if(item==null)throw new IllegalArgumentException("unknown item marker");m.appendReplacement(out,Matcher.quoteReplacement(item.name()));}m.appendTail(out);return out.toString();
 }
 @Transactional(noRollbackFor=IllegalArgumentException.class)
 public void checkPortableClaims(String text,GameScene scene,Plan plan,String actor){
  checkPortableClaims(text,scene,plan,actor,JSON.createArrayNode());
 }
 @Transactional(noRollbackFor=IllegalArgumentException.class)
 public void checkPortableClaims(String text,GameScene scene,Plan plan,String actor,JsonNode uses){
  checkPortableClaims(text,scene,plan,actor,uses,com.fasterxml.jackson.databind.node.MissingNode.getInstance());
 }
 @Transactional(noRollbackFor=IllegalArgumentException.class)
 public void checkPortableClaims(String text,GameScene scene,Plan plan,String actor,JsonNode uses,JsonNode nonItemUses){
  checkPortableClaims(text,scene,plan,actor,uses,nonItemUses,List.of());
 }
 @Transactional(noRollbackFor=IllegalArgumentException.class)
 public void checkPortableClaims(String text,GameScene scene,Plan plan,String actor,JsonNode uses,JsonNode nonItemUses,List<ObservedUse> observations){
  var declared=select(uses,plan.refs(),actor);
  var pattern=Pattern.compile("(?:取来|取出|拿出|掏出|拾起|捡起|拾来|捡来|手持|握着|拿着|使用|持着|削|劈|切|消耗|加工|锯|用(?!来|于|以|作))(?:了|着|一把|一根|一个|自己的)?([^，。！？；\\n]{1,20})");var m=pattern.matcher(text);
  String initial=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];
  while(m.find()){
   if(m.group(1).matches("^(?:不了|不得|不能|不动|不开|不出|不到|不成|不完|不下|不起|不透|不破|不穿).*"))continue; // Explicit failure after the verb is not actual tool use.
   if(m.group(1).matches("^(?:[一二三四五六七八九十百两几数多0-9]+)(?:把|次|回|下|步|遍|段|片|团)(?:$|(?:便|就|后|时|之后|以后|再|又).*)"))continue; // A counted operation with no noun is not an item, e.g. 掏出两三把便检查.
   int left=m.start();while(left>0 && "，。！？；\n".indexOf(text.charAt(left-1))<0)left--;
   String prefix=text.substring(left,m.start());
   if(prefix.matches("(?s).*(未能|没有|没能|不能|无法|不曾|准备|打算|计划|想要|希望|将要|需要|如果|倘若|缺少|不愿|别|勿).*" ) || prefix.endsWith("不"))continue;
   String object=m.group(1).replaceFirst("^(?:了|着|去|开|成|下|出){0,2}(?:一(?:把|根|个|段|片|块|枚|些))?(?:随身(?:的)?|自己(?:的)?|自带(?:的)?|已有(?:的)?|背包(?:里|中)(?:的)?)?","");if(object.startsWith("[[ITEM:"))continue;
   String noun=object.split("[与和并在向沿对将把]|(?:插入|斜插|撬|凿|削|劈|切|磨|加工|检查|探|走|蹲|贴|观察)",2)[0];
   boolean known=plan.refs().values().stream().anyMatch(i->noun.startsWith(i.name()));
   if(known){
    if(declared.stream().anyMatch(i->noun.startsWith(i.name())))continue;
    // Exempt this one observed use, never the rest of its sentence or a later first-person use.
    if(observations.stream().anyMatch(o->!actor.equals(o.sourceActorId()) && noun.startsWith(o.itemName())
       && prefix.matches("(?s).*(?:看见|看到|见到|目睹|注意到|见)"+Pattern.quote(o.label())+"(?:已经|已|正在|正)?$")))continue;
    throw new IllegalArgumentException("portable item must declare its verified reference: "+noun);
   }
   if(ActionExecutionContract.declaredNonItem(object,nonItemUses))continue;
   if(nonItemUses.isArray() && ActionExecutionContract.bodyPhrase(object))continue;
   // Ordinary worn fabric is an outfit detail, not a new inventory item or anatomical part.
   // This cannot authorize hidden blades, enchanted clothing, armor, trading or item creation.
   if(ordinaryAttirePhrase(object))continue;
   if(!nonItemUses.isArray() && (noun.isBlank() || noun.startsWith("手") || noun.startsWith("身体") || noun.startsWith("力") || noun.startsWith("脚") || noun.startsWith("声音") || noun.startsWith("目光") || noun.startsWith("意识")))continue;
   // Anatomical nouns followed by a bodily action are not portable inventory. Do not accept e.g. 指甲刀.
   if(!nonItemUses.isArray() && noun.matches("^(?:指节|指甲|指尖|指腹|掌心|掌根|掌缘|掌背|拳头|拇指|食指|中指|无名指|小指|双手|双脚|肩膀|肩部|肩|肘部|肘|膝盖|膝部|膝|前臂|后背|背部|腰部|腰|牙齿|牙|嘴唇|嘴|舌头|舌|头部)(?:$|[轻重稳缓猛按压敲扣拍抵撑捏碰拂触抚顶推握捶伸抓夹踩踢托扯拉挪移侧护挡撞勾弯屈打扫点拨弹搓揉刮挑划抠擦刨挤掐捻剥戳抬抖拖摇扳拈啃咬].*)"))continue;
   // A verified tool's tip/handle is part of that tool, not a new portable object.
   var part=Pattern.compile("^(?:这|那|其)?([刀剑针杆棍斧锤绳弓枪杖])(尖|刃|背|柄|身|头|尾|端|面|扣|钩|齿|沿).*$").matcher(noun);
   if(part.matches() && declared.stream().filter(i->i.name().endsWith(part.group(1))).count()==1)continue;
   var alias=Pattern.compile("^(?:这|那|其)?([刀剑针杆棍斧锤绳弓枪杖])$").matcher(noun);
   // Only an already declared, actor-owned, unique tool may use a bare type abbreviation.
   if(alias.matches() && declared.stream().filter(i->i.name().endsWith(alias.group(1))).count()==1)continue;
   if(nonItemUses.isArray())throw new IllegalArgumentException("actual resource needs verified item ref, anatomical identity or declared existing facility: "+noun);
   if(!initial.contains(noun) || !noun.matches(".*(?:闸|门|槽|轴|绳结|活结|墙|床|柱|石阶|石壁|池|祭坛|洞口|幡|淤泥|泥块|碎石|浮泥|砂土|积雪).*"))throw new IllegalArgumentException("portable item has no registered source: "+noun+"; matched phrase: "+m.group(0).substring(0,Math.min(120,m.group(0).length())));
  }
 }
 public static boolean ordinaryAttirePhrase(String phrase){
  return phrase.matches("^(?:自己的|身上的)?(?:衣袖|袖子|袖口|袖角|衣角|衣襟|袍角|裙摆|靴尖|鞋尖)(?:$|(?:轻轻|轻|顺手|替他|替她|帮他|帮她|为他|为她)?(?:擦|拂|遮|掩|压平|抚平|理|整理|拭|碰|抵|扫).*)");
 }
}
