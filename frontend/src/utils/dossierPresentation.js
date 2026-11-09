import {dossierLabel,dossierValue} from './dossierLabels'

export const unreadableDossier='这条记录暂未整理成可读内容。'
export const maxDossierDepth=16
const wireFields=new Set([
 'id','ref','templateVersion','protocolVersion','schema','schemaVersion','operationId','logId','decisionId','knowledgeId','entryId','lastKnowledgeId',
 'source_ref','sourceRef','source_god_id','recordedSceneId','frame','historyOrder','reviewedAt','recordingMethod','subjectType',
 'freshnessMeaning','psychologyMeaning','sceneStateMeaning','talentMeaning','playerDecisionMeaning','movementMeaning','pendingSpeechMeaning','reactionContinuity',
 'rawResponse','originalResponse','parsedJson','messages','prompt','systemPrompt','modelPhase','sourceHash','archetype','culturalStyle','__proto__','prototype','constructor'
])
const referenceFields=new Set(['roleId','roleIds','actorId','targetId','withRoleId','holderId','legalOwnerId','ownerId','subject','itemRef','sceneId'])
const enumFields=new Set(['source','source_type','recordType','kind','effectKind','type','state','status','executionStatus','presence','epistemicKind','scope','visibility','narrativeTier','precision','unit','gender','ageGroup','species'])
const literalFields=new Set(['name','playerName','withName','personalHistory','text','speech'])
const encodedStart=text=>/^\{\s*(?:["'}]|$|[A-Za-z_$][\w$]*\s*:)/.test(text) || /^\[\s*(?:[\[{"'\]\d-]|true\b|false\b|null\b|$)/.test(text) || text.startsWith('```')

function knowledgeEntries(text){
 const headers=[...text.matchAll(/(?:^|\n)【记录序号\d+[（(]非故事时刻[）)][；;]来源[：:]([A-Z_]+)】/g)]
 if(!headers.length || headers[0].index!==0)return null
 return headers.map((header,index)=>{
  const body=text.slice(header.index+header[0].length,headers[index+1]?.index ?? text.length).trim()
  const split=body.indexOf('：')
  return {title:split<0?'已保存记录':body.slice(0,split),source:header[1],content:readDossierValue(split<0?body:body.slice(split+1))}
 })
}

// Old saves contain JSON strings inside valid objects/arrays. Only decode
// complete containers; ordinary prose and its quotation marks stay literal.
export function readDossierValue(value){
 let current=value
 for(let layer=0;layer<4 && typeof current==='string';layer++){
  let text=current.trim()
  const records=knowledgeEntries(text);if(records)return records
  if(text.startsWith('```')){
   const fenced=text.match(/^```(?:json|javascript|js)?\s*([\s\S]*?)\s*```$/i)
   if(!fenced)return unreadableDossier
   text=fenced[1].trim()
  }
  if(!encodedStart(text) && !text.startsWith('"'))return current
  let parsed
  try{parsed=JSON.parse(text)}catch{return encodedStart(text)?unreadableDossier:current}
  if(parsed!==null && typeof parsed==='object')return parsed
  if(typeof parsed==='string' && encodedStart(parsed.trim())){current=parsed;continue}
  return current
 }
 return typeof current==='string' && encodedStart(current.trim())?unreadableDossier:current
}
export function isStructuredDossier(value){
 const decoded=readDossierValue(value)
 return decoded===unreadableDossier || decoded!==null && typeof decoded==='object'
}
const hasValue=value=>value!=null && !(typeof value==='string' && !value.trim()) && !(Array.isArray(value) && !value.length) && !(typeof value==='object' && !Object.keys(value).length)
export function dossierEntries(value){
 return Object.entries(value).filter(([key])=>!wireFields.has(key) && !(key==='withRoleId' && value.withName))
  .map(([key,child])=>[key,readDossierValue(child)]).filter(([,child])=>hasValue(child))
}
function named(value,names){return typeof value==='string' && Object.hasOwn(names,value) && typeof names[value]==='string'?names[value]:null}
function referenceFallback(field,value){
 if(field==='itemRef' || String(value).startsWith('item_'))return '名称尚未记录的物品'
 if(field==='sceneId' || String(value).startsWith('scene_'))return '名称尚未记录的地点'
 if(field==='targetId' || field==='subject')return '尚未注明的对象'
 return '姓名尚未记录的人物'
}
export function dossierText(value,fieldName='',names={}){
 if(fieldName==='field')return dossierLabel(String(value))
 const known=named(value,names);if(known)return known
 if(referenceFields.has(fieldName) && typeof value==='string' && !/[\u3400-\u9fff]/.test(value))return referenceFallback(fieldName,value)
 const text=literalFields.has(fieldName) && typeof value==='string'?value:dossierValue(value)
 if(enumFields.has(fieldName) && text===String(value) && /^[A-Z][A-Z0-9_:-]*$/.test(text))return '尚未注明'
 return text.replace(/\b(?:npc|player|god|scene|sess|item|plot|event)_[A-Za-z0-9_]{6,}\b/g,id=>named(id,names) || referenceFallback('',id))
}
export function dossierRecordType(value){return value?dossierText(value,'recordType'):'个人记录'}
export function dossierNamesFrom(data){
 const names=Object.create(null),character=data?.character || {}
 const put=(id,name)=>{if(typeof id==='string' && typeof name==='string' && name.trim())names[id]=name}
 for(const group of [data?.roles,data?.activeCharacters])if(Array.isArray(group))for(const role of group)put(role.id,role.name)
 put(character.id,character.name)
 const dossier=readDossierValue(character.dossier)
 for(const group of [character.inventory,dossier?.currentState?.items])if(Array.isArray(group))for(const item of group)put(item.ref,item.name)
 if(Array.isArray(character.location))for(const place of character.location)put(place.scene_id,place.location_name)
 return names
}
