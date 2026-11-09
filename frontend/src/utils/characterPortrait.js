// Matching concerns public artwork only; it never changes a story result.
import {characterPortrait as legacy,portraitTypes,sceneCast as legacyCast} from './legacyCharacterPortrait'
import approvedPortraits from './approvedPortraits.json'
import {rankPortraits,rankClosestPortraits,stablePortrait,portraitSpecies} from './portraitMatching'
import {symbolicPortrait} from './portraitFallback'
export {portraitTypes}
export const portraitCatalog=[
 {key:'western-crimson-gown-female',gender:'female',ages:['YOUNG','ADULT'],species:'human',role:'NOBLE',style:'CRIMSON_GOWN'},
 {key:'western-rose-elf-female',gender:'female',ages:['YOUNG','ADULT'],species:'elf',role:'ROSE_ELF'},
 {key:'western-elf-female',gender:'female',ages:['YOUNG','ADULT'],species:'elf',role:'ELF'},
 {key:'western-knight-male',gender:'male',ages:['YOUNG','ADULT'],species:'human',role:'KNIGHT'},
 {key:'western-noble-female',gender:'female',ages:['YOUNG','ADULT'],species:'human',role:'NOBLE'},
 {key:'western-warrior-female',gender:'female',ages:['YOUNG','ADULT'],species:'human',role:'WARRIOR'},
 {key:'western-cleric-male',gender:'male',ages:['ELDER'],species:'human',role:'CLERIC'},
 {key:'western-dragon-rider-male',gender:'male',ages:['YOUNG','ADULT'],species:'human',role:'DRAGON_RIDER'},
 ...approvedPortraits
]
for(const a of portraitCatalog) {
 if(!a.approved)a.legacy=true
 if(a.key==='western-rose-elf-female')a.exclusiveRole='ROSE_ELF'
 if(a.key==='P261026-05')a.roles=['ATTENDANT','HOUSEKEEPER']
 if(a.approved)portraitTypes[a.key]=a.label
}
Object.assign(portraitTypes,{'western-elf-female':'精灵旅人','western-knight-male':'骑士','western-noble-female':'礼服贵族','western-warrior-female':'战士','western-cleric-male':'教宗','western-dragon-rider-male':'龙骑士'})
portraitTypes['western-rose-elf-female']='玫瑰精灵'
portraitTypes['western-crimson-gown-female']='酒红礼服'
const cinematicKeys=new Set(['western-crimson-gown-female','western-elf-female','western-rose-elf-female','western-knight-male','western-noble-female','western-cleric-male','western-dragon-rider-male'])
export function portraitAssetSource(asset){return asset.src || `/art/refraction-v${asset.cinematic || cinematicKeys.has(asset.key)?'2':'1'}/${asset.key}.png`}
export function portraitStage(portrait={}){
 if(portrait.key==='western-knight-male')return {backdrop:'/art/refraction-v2/stage-knight-night.png',accent:'#718cae',night:true}
 if(portrait.key==='western-rose-elf-female')return {accent:'#a92c43'}
 return {accent:'#1758ed'}
}
export function sceneBackdrop(scene={},atmosphere={key:'default'}){
 const style=scene.worldSetting?.culturalStyle
 if(style==='WESTERN')return /车站|月台|铁路|列车/.test(scene.locationName || '')?'/art/refraction-v2/stage-clock-station.png':'/art/refraction-v1/stage-western.png'
 if(!style || style==='EASTERN')return '/art/paper-theatre-v1/stage-'+atmosphere.key+'.png'
 return null
}
const normalize=value=>({东方:'EASTERN',西方:'WESTERN',现代:'MODERN',科幻:'SCIFI'}[value] || String(value || '').toUpperCase())
function selectPortrait(person={},scope='DREAMER',world={}){
 const publicInfo=person.publicAppearance || person.known?.appearance || {}
 const info=scope==='OMNISCIENT'?{...publicInfo,...(person.appearance || {})}:publicInfo
 const original=legacy(scope==='OMNISCIENT'?{...person,appearance:info}:person,scope)
 const worldStyle=normalize(world.culturalStyle || person.worldStyle || info.worldStyle)
 const culture=normalize(info.culturalStyle || worldStyle || 'EASTERN')
 const gender=['女','女性','female'].includes(String(info.gender).toLowerCase())?'female':['男','男性','male'].includes(String(info.gender).toLowerCase())?'male':info.gender==='unknown'?'unknown':/woman|girl/.test(original.key || '')?'female':['man','elder'].includes(original.key)?'male':'unknown'
 const species=portraitSpecies(info.species,info)
 // Long-lived peoples' chronological age is not their visible age. An explicit
 // apparent age takes priority over a general age group from older packets.
 const ageless=['elf','vampire'].includes(species)
 const age=Number.isFinite(info.apparentAge)?info.apparentAge:ageless?null:Number.isFinite(info.age)?info.age:null
 const visibleAgeGroup=info.apparentAgeGroup || (Number.isFinite(info.apparentAge)?(age<14?'CHILD':age<30?'YOUNG':age>=60?'ELDER':'ADULT'):null)
 const group=String(visibleAgeGroup || info.ageGroup || (age!==null?(age<14?'CHILD':age<30?'YOUNG':age>=60?'ELDER':'ADULT'):ageless?'ADULT':original.key==='child-girl'?'CHILD':/^elder/.test(original.key || '')?'ELDER':/^young/.test(original.key || '')?'YOUNG':'ADULT')).toUpperCase()
 const unknown=reason=>({key:null,src:null,label:'形象尚未记明',reason,culture,gender,ageGroup:group})
 if(worldStyle && worldStyle!=='MIXED' && culture!==worldStyle)return unknown('外貌与世界风格不一致')
 if(info.gender==='unknown' && original.key!=='deity')return unknown('公开外貌资料不足')
 const ranked=['EASTERN','WESTERN','MODERN'].includes(culture) && gender!=='unknown' && group!=='CHILD'
  ?rankPortraits(portraitCatalog,info,{culture,gender,group,species}):[]
 let match=stablePortrait(ranked,person,scope,{culture,gender,group,species})
 let closest=false
 if(culture==='EASTERN' && !match){
  // A symbolic divine picture is not a reliable sex/age portrait.
  if(original.key==='deity')return {...original,src:null,culture}
  const replacements={woman:'eastern-woman',man:'eastern-man'}
  if((!species || ['human','unknown'].includes(species)) && original.src)return {...original,src:replacements[original.key]?'/art/refraction-v1/'+replacements[original.key]+'.png':original.src,culture}
 }
 if(!['EASTERN','WESTERN','MODERN'].includes(culture))return unknown('该题材素材尚未建立')
 if(gender==='unknown' || group==='CHILD')return unknown('没有匹配的年龄或性别素材')
 if(!match){
  match=stablePortrait(rankClosestPortraits(portraitCatalog,info,{culture,gender,group,species}),person,scope,{culture,gender,group,species,matching:'closest'})
  closest=!!match
 }
 const cinematic=match && (match.cinematic || cinematicKeys.has(match.key))
 return match?{key:match.key,portraitAssetId:match.key,src:portraitAssetSource(match),cinematic,label:portraitTypes[match.key],culture,gender,ageGroup:group,matchKind:closest?'closest':'specific',...(match.framing?{framing:match.framing}:{})}:unknown('没有兼容题材或性别的素材')
}
export function characterPortrait(person={},scope='DREAMER',world={}){
 person=person && typeof person==='object'?person:{}
 world=world && typeof world==='object'?world:{}
 const result=selectPortrait(person,scope,world)
 // An actual individual always receives stage art. An empty cast stays empty;
 // the stage can separately show a symbolic journey illustration while alone.
 if(result.src || !(person.id || person.godId || person.name || person.godName))return result
 return {...symbolicPortrait(person,scope,world,result),reason:result.reason}
}
export function sceneCast(scene={}){
 return legacyCast(scene).map(p=>({...p,worldStyle:scene.worldSetting?.culturalStyle || p.publicAppearance?.worldStyle}))
}
