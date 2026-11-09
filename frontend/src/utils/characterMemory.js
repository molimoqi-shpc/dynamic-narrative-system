import {readDossierValue,dossierEntries,dossierText,dossierRecordType} from './dossierPresentation'
import {dossierLabel} from './dossierLabels'

const stateTypes=new Set(['OBSERVED_STATE','ITEM_STATE','ITEM_PROFILE','PLAYER_RELATION','STORY_CONTINUITY','ROLE_STORY_STATE','APPEARANCE','SCENE_VIEW','BODY_STATE','VITAL_STATE','VISITED_SCENE','SCENE_SEEN','STORY_CLOCK','ROLE_SCHEDULE','SCENE_STATE','SCENE_OBJECTS','SEEN_ROLE','SEEN_FACILITY','ITEM_PLACEMENT','ROLE_PROCESS'])
export const isMemoryState=entry=>stateTypes.has(entry.type)

// Read-only projection: exact saved prose gets reading width, technical structure stays expandable.
export function characterMemory(entry,names={}){
 const value=readDossierValue(entry.content),blocks=[],seen=new Set()
 const add=(key,text)=>{
  const decoded=readDossierValue(text)
  if(typeof decoded!=='string' || !decoded.trim())return false
  const prose=dossierText(decoded,key,names).trim()
  if(!seen.has(prose)){seen.add(prose);blocks.push({key,label:key==='text'?'':({summary:'记录摘要',basis:'记录依据',description:'记述'}[key] || dossierLabel(key)),paragraphs:prose.split(/\n\s*\n+/).filter(Boolean)})}
  return true
 }
 let details=null
 if(typeof value==='string')add('text',value)
 else if(Array.isArray(value) && value.every(item=>typeof item==='string'))value.forEach(item=>add('text',item))
 else if(value && typeof value==='object' && !Array.isArray(value)){
  details={...value}
  for(const key of ['personalHistory','profile','experience','text','process','speech','situation','thought','intention','actionDirection','summary','basis','description'])if(Object.hasOwn(details,key) && add(key,details[key]))delete details[key]
  const direction=readDossierValue(details.direction)
  if(direction && typeof direction==='object' && !Array.isArray(direction)){
   const remaining={...direction}
   for(const key of ['thought','intention','actionDirection','communicationIntent','boundaries'])if(Object.hasOwn(remaining,key) && add(key,remaining[key]))delete remaining[key]
   if(dossierEntries(remaining).length)details.direction=remaining;else delete details.direction
  }
  for(const key of ['recordType','visibility','startSeconds','endSeconds','executionStatus'])delete details[key]
  if(!dossierEntries(details).length)details=null
 }else details=value
 const subjective=['ROLE_PROFILE','ROLE_DECISION','ROLE_PENDING','ROLE_PLAN','SCENE_ATTEMPT'].includes(entry.type) || value?.executionStatus==='PROPOSED'
 const type=dossierRecordType(value?.recordType || entry.type)
 const title=entry.title && !['角色经历','主API人物资料'].includes(entry.title)?entry.title:type
 const seconds=key=>typeof value?.[key]==='number' && Number.isFinite(value[key]) && value[key]>=0?value[key]:null
 return {title,type,subjective,blocks,details,start:seconds('startSeconds'),end:seconds('endSeconds')}
}
