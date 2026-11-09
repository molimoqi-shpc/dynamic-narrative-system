package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Optional display metadata only. Unknown or malformed details never reject a story. */
public final class PublicAppearanceSchema {
 private PublicAppearanceSchema(){}
 private static final ObjectMapper JSON=new ObjectMapper();
 public static final String PROMPT="""
  【可选公开外貌细节】characterAppearances的archetype是公开职业/形象名称，可按正文使用SWORDSMAN（武侠剑客）、NECROMANCER、ALCHEMIST、MECHANIC、PIRATE_CAPTAIN等，不限于示例枚举。species可用human/elf/dwarf/dragonborn/vampire或实际种族；未知不猜，吸血鬼身份未公开不写vampire。可附apparentAgeGroup或apparentAge（外表年龄优先于实际寿数）、hairColor、hairLength、eyeColor、clothing、visibleEquipment、portraitKeywords；后三项可为短字符串或字符串数组。当前亲见的机械义肢可附visibleAnatomy:{leftForearm:"机械义肢或自然血肉",rightHand:"自然血肉"}；当前亲见的随行坐骑可附visibleMount:{present:true,species:"实际种类",coat:"毛色"}，坐骑离开写false。只记主角当前亲见/已知的外貌与公开变化，不能把角色私密心理、真实血统、旧时装备或将来意愿放入此表，不因图片库存增造角色、道具或外貌。细节无依据可以省略，缺图和表缺项均不影响正文成立或保存。
  """;
 public static ObjectNode sanitize(JsonNode raw){
  var out=JSON.createObjectNode();if(raw==null || !raw.isObject())return out;
  copyEnum(raw,out,"gender",Set.of("female","male","unknown"));
  for(String field:List.of("ageGroup","apparentAgeGroup"))copyEnum(raw,out,field,Set.of("CHILD","YOUNG","ADULT","ELDER","UNKNOWN"));
  for(String field:List.of("culturalStyle","archetype","species","portraitStyle"))copyText(raw,out,field,80);
  copyText(raw,out,"basis",600);
  for(String field:List.of("hairColor","hairLength","eyeColor","clothing","visibleEquipment","portraitKeywords","scaleColor","wingColor","hornColor")){
   var value=raw.path(field);
   if(value.isTextual())copyText(raw,out,field,600);
   else if(value.isArray()){
    var items=JSON.createArrayNode();
    for(var item:value)if(item.isTextual() && !item.asText().isBlank() && items.size()<32)items.add(cap(item.asText().strip(),160));
    if(!items.isEmpty())out.set(field,items);
   }
  }
  for(String field:List.of("age","apparentAge")){
   var age=raw.path(field);if(age.isIntegralNumber() && age.canConvertToInt() && age.asInt()>=0)out.put(field,age.asInt());
  }
  if(raw.path("divineRevealed").isBoolean())out.put("divineRevealed",raw.path("divineRevealed").asBoolean());
  for(String field:List.of("anatomy","visibleAnatomy"))if(raw.path(field).isObject()){
   var parts=JSON.createObjectNode();
   for(String part:List.of("leftForearm","leftArm","leftHand","rightForearm","rightArm","rightHand")){
    copyText(raw.path(field),parts,part,160);
    if(raw.path(field).path(part).isBoolean())parts.put(part,raw.path(field).path(part).asBoolean());
   }
   if(!parts.isEmpty())out.set(field,parts);
  }
  for(String field:List.of("visibleMount","mount")){
   var mount=raw.path(field);
   if(mount.isBoolean())out.put(field,mount.asBoolean());
   else if(mount.isObject()){
    var view=JSON.createObjectNode();
    for(String key:List.of("species","coat","mane","horn","armor"))copyText(mount,view,key,120);
    for(String key:List.of("present","visible"))if(mount.path(key).isBoolean())view.put(key,mount.path(key).asBoolean());
    if(!view.isEmpty())out.set(field,view);
   }
  }
  return out;
 }
 private static void copyEnum(JsonNode raw,ObjectNode out,String field,Set<String> allowed){if(raw.path(field).isTextual() && allowed.contains(raw.path(field).asText()))out.set(field,raw.path(field));}
 private static void copyText(JsonNode raw,ObjectNode out,String field,int max){if(raw.path(field).isTextual() && !raw.path(field).asText().isBlank())out.put(field,cap(raw.path(field).asText().strip(),max));}
 private static String cap(String text,int max){return text.length()>max?text.substring(0,max):text;}
}
