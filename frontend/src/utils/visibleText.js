// View-only compatibility for old saves. IDs remain intact in requests and persistence.
export function visibleText(value,names={}) {
  let s=String(value || '').replace(/[；;]不提供回血、无敌或强制剧情成功[。.]?/g,'。')
  s=s.replace(/【开局增益：(zhi|hui|dou|wen)\+(\d+)】/g,(_,key,n)=>`基础${({zhi:'知',hui:'慧',dou:'斗',wen:'闻'})[key]} +${n}。`)
  s=s.replace(/\s*\((?:player|npc|god|sess|scene)_[A-Za-z0-9_]+\)?/g,'')
  s=s.replace(/\s*（(?:player|npc|god|sess|scene)_[A-Za-z0-9_]+）?/g,'')
  s=s.replace(/(?:player|npc|god|sess|scene)_[A-Za-z0-9_]{3,}/g,id=>names[id] || (id.startsWith('scene_')?'此处':'在场人物'))
  s=s.split('\n').filter(line=>!/(【强约束】|生成的对白与内心活动必须|六维计算版本|实际六维：|规则引擎最终裁决类型)/.test(line)).map(line=>line.replace(/\s*\|\s*事实：.*$/,'')).join('\n')
  return s.replace(/【(?:OBSERVE|EXAMINE|TALK|NARRATE|WAIT|UNKNOWN|ATTACK):([^】]*)】/g,'$1').replace(/\bNO_OP\b/g,'暂无属性变化').replace(/\btrust_value\b/g,'信任')
}
// Legacy system notes are presented as attributed observations, without changing stored claims.
export function sceneNote(value,names={}) {
 return visibleText(value,names)
  .replace(/与在场人物公开交谈；发言内容尚未核实。/g,'与在场人物交谈。')
  .replace(/提出了新的说法（尚未核实）：/g,'说：')
  .replace(/提出了需要关注的公开说法；其内容尚未核实。/g,'说出了值得留意的话。')
  .replace(/重申此前说法，没有新的可确认信息。/g,'重申此前的说法。')
}
export function sourceLabel(entry) {
 return ({HEARD:'亲耳听见',INQUIRED:'打听所得',INVESTIGATED:'调查所得',TOLD:'他人转述'})[entry.sourceType] || String(entry.sourceLabel || '').replace(/ · (?:话语)?待核验/g,'')
}
export const actorName=(id,names={})=>names[id] || (id==='narrator'?'旁白':id==='system_inquiry'?'传闻':'在场人物')
