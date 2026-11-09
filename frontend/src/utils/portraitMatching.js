// Artwork selection reads public display facts only; it never calls a model.
const roleAliases = {
 KNIGHT:['骑士'], WARRIOR:['战士'], GUARD:['护卫','卫兵'],
 NOBLE:['贵族'], DIPLOMAT:['外交官'], SOCIALITE:['名媛'],
 MAGE:['法师'], SCHOLAR:['学者','研究员'], ACADEMY_MAGE:['学院法师'],
 ATTENDANT:['侍从','执事'], HOUSEKEEPER:['女管家'], ELF:['精灵'],
 RANGER:['游侠'], SCOUT:['斥候'], ENVOY:['使者'],
 CLERIC:['教宗','教皇','神职'], DRAGON_RIDER:['龙骑士'], ROSE_ELF:['玫瑰精灵'],
 PALADIN:['圣骑士'], COMMANDER:['统领','指挥官','团长'], MERCENARY:['佣兵'],
 THIEF:['盗贼','窃贼','盗匪'], ROGUE:['游荡者'],
 PRIESTESS:['女祭司','圣女'], PRIEST:['祭司','牧师','神职者'],
 DRAGONBORN:['龙裔','半龙人'], NUN_KNIGHT:['修女骑士','圣殿修女骑士'],
 DUAL_SWORD_KNIGHT:['双剑骑士','双剑女骑士','双剑男骑士','双刃骑士'],
 UNICORN_CAVALIER:['独角兽骑兵','独角兽骑士'],
 WITCH:['女巫'], DIVINER:['占卜师','预言师'],
 STORM_MAGE:['风暴法师','雷电法师','雷法师'],
 BARD:['吟游诗人'], MUSICIAN:['乐师','演奏者'], SINGER:['歌者','歌手'],
 ALCHEMIST:['炼金术士','炼金师'], ARTIFICER:['魔工师','造物师'],
 MECHANIC:['机械师'], ENGINEER:['工程师'], SMITH:['铁匠','锻造师'],
 DARK_KNIGHT:['暗黑骑士','黑暗骑士','黑骑士'],
 APOCALYPSE_WARRIOR:['天启战士'], NECROMANCER:['死灵法师','亡灵法师'],
 VAMPIRE:['吸血鬼','血族'], PIRATE_CAPTAIN:['海盗船长','海盗女王'],
 PIRATE:['海盗','海盗船员'], CAPTAIN:['船长'],
 SWORDSMAN:['剑客','侠客','女侠','侠女','剑侠','武侠剑客']
}
const hairColors = {
 silver:['银白','银灰','月白','霜白','silver','platinum','moon-white'],
 blonde:['金发','金色头发','灰金','浅金','blonde','ash-blond','champagne'],
 brown:['棕发','栗棕','棕色','灰褐','褐发','棕褐','chestnut','brown','dark-brown'],
 black:['黑发','乌黑','black'], red:['红发','赤发','酒红','red','burgundy']
}
const hairLengths = {long:['长发','长卷发','long'],short:['短发','short'],up:['盘发','半束','发髻','挽发','束髻','updo','tied']}
const eyeColors = {blue:['蓝','blue'],violet:['紫','violet','lilac'],green:['绿','green'],brown:['棕','brown','hazel'],amber:['琥珀','amber']}
const list = value => Array.isArray(value) ? value.filter(x=>typeof x==='string') : typeof value==='string' ? [value] : []
const lower = value => String(value || '').toLowerCase()
// Ignore uncertain/negative clauses, rather than matching "并不是银发法师".
const visibleText = value => list(value).join('；').split(/[。；;！？\n]/).filter(s=>! /不是|并非|没有|未曾|可能|据说|传闻|听说/.test(s)).join('；').toLowerCase()
function contains(text, token) {
 const word=lower(token)
 if(!word)return false
 if(/^[a-z0-9_ -]+$/.test(word)) {
  const escaped=word.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
  return new RegExp('(?:^|[^a-z])'+escaped+'(?:$|[^a-z])','i').test(text)
 }
 return text.includes(word)
}
const hits = (text, tokens=[]) => tokens.some(token=>contains(text,token))
function tags(text, groups) {
 return Object.entries(groups).filter(([,aliases])=>hits(text,aliases)).map(([key])=>key)
}
function overlap(known, pictured) {
 return !known.length || !pictured.length ? 0 : known.some(t=>pictured.includes(t)) ? 1 : -1
}
export function portraitRole(value) {
 const normalized=String(value || '').toUpperCase()
 return Object.keys(roleAliases).find(key=>key===normalized || roleAliases[key].includes(value)) || normalized
}
export function portraitSpecies(value, info={}) {
 const species=({'人类':'human','精灵':'elf','矮人':'dwarf','吸血鬼':'vampire','血族':'vampire','龙裔':'dragonborn','半龙人':'dragonborn','dragon-born':'dragonborn','dragonkin':'dragonborn','未知':'unknown'}[lower(value)] || lower(value))
 // Old packets use "other" for nonhuman peoples. Only an explicitly public
 // label for this person can refine it; names and hidden bloodlines stay out.
 if(['','unknown','other'].includes(species) && (portraitRole(info.archetype)==='DRAGONBORN' || /龙裔|半龙人/.test(visibleText(info.basis))))return 'dragonborn'
 if(['','unknown','other'].includes(species) && (portraitRole(info.archetype)==='VAMPIRE' || /吸血鬼|血族/.test(visibleText(info.basis))))return 'vampire'
 return species
}
function publicMount(info) {
 const data=info.visibleMount ?? info.mount
 if(data===false || data && typeof data==='object' && (data.present===false || data.visible===false))return null
 const explicit=data && typeof data==='object' && (data.present===true || data.visible===true)
 const species=explicit ? lower(data.species) : ''
 if(explicit && !['unicorn','独角兽'].includes(species))return null
 // A profession, helmet ornament, historical recollection or somebody else's
 // animal is not evidence of a mount accompanying this depicted character.
 const clauses=visibleText(info.basis).split(/[，,；;。]/)
 const proof=clauses.filter(c=>
  /独角兽(?!骑兵|骑士|纹|徽)/.test(c) &&
  /身旁|身边|身后|一旁|旁边|左侧|右侧|牵着|牵住|轻抚|抚摸|并肩|伴着|带着|站着|站在/.test(c) &&
  !/另一|别人|他人|她人|壁画|画像|画上|雕像|雕刻|图案|纹样|纹章|徽记|盔饰|印着|过去|曾经|从前|回忆|想要|寻找|计划|离开|不在|未见|无独角兽/.test(c)
 )
 if(!explicit && !proof.length)return null
 const text=explicit && data.coat ? lower(data.coat) : proof.join('；')
 const coat=/苹果斑|灰色|dapple|gray|grey/.test(text)?'dapple-gray':/黑色独角兽|black/.test(text)?'black':/白色|纯白|white/.test(text)?'pure-white':null
 return {species:'unicorn',coat}
}
// A prosthetic limb is a visible physical feature, never inferred from a job.
// This helper only receives the appearance permitted by the current perspective.
function prosthesisCompatible(asset, info, text) {
 if(!asset.requiredProsthesis)return true
 const anatomy=info.visibleAnatomy || info.anatomy || {}
 const mechanical=value=>/mechanical|prosthe|机械|义肢|义臂/i.test(String(value || '')) && !/non-mechanical|非机械/i.test(String(value || ''))
 const natural=value=>value===false || /natural|organic|bare|天然|自然|血肉|非机械/i.test(String(value ?? ''))
 const left=anatomy.leftForearm ?? anatomy.leftArm
 const right=anatomy.rightForearm ?? anatomy.rightArm ?? anatomy.rightHand
 const clauses=text.split(/[，,；;。]/).filter(c=>!/曾经|过去|从前|别人|他人|另一|壁画|图案|徽记/.test(c))
 // Holding a mechanical core does not make the hand itself mechanical.
 const leftProof=clauses.some(c=>/左(?:侧)?(?:前臂|手臂|臂|手|肘以下)[^，；。握持托提拿举触抚]{0,12}(?:机械|义肢|义臂)|左机械(?:前臂|臂|手)|(?:mechanical|prosthetic) left (?:forearm|arm|hand)|left (?:forearm|arm|hand).{0,12}(?:mechanical|prosthetic)/i.test(c))
 const rightProof=clauses.some(c=>/右(?:侧)?(?:前臂|手臂|臂|手|肘以下)[^，；。握持托提拿举触抚]{0,12}(?:机械|义肢|义臂)|右机械(?:前臂|臂|手)|(?:mechanical|prosthetic) right (?:forearm|arm|hand)|right (?:forearm|arm|hand).{0,12}(?:mechanical|prosthetic)/i.test(c))
 const conflict=/双(?:手|臂)(?:都是|均为|均是|为|是)?(?:天然|自然|血肉)/.test(text)
  || clauses.some(c=>/左(?:手|臂|前臂)/.test(c) && /天然|自然|血肉|非机械/.test(c))
 return !natural(left) && !mechanical(right) && !rightProof && !conflict && (mechanical(left) || leftProof)
}
export function rankPortraits(catalog, info, {culture,gender,group,species}) {
 const role=portraitRole(info.archetype), style=String(info.portraitStyle || '').toUpperCase()
 const basis=visibleText(info.basis)
 const keywords=visibleText(info.portraitKeywords)
 const clothing=visibleText([ ...list(info.clothing), ...list(info.visibleEquipment), basis, keywords ])
 const mount=publicMount(info)
 const hair=visibleText([...list(info.hairColor), ...list(info.hairLength)])
 // Only hair-specific phrases in prose imply a hair color, not silver armor.
 const proseHair=(basis+'；'+keywords).match(/(?:银白|银灰|月白|霜白|灰金|浅金|栗棕|棕色|乌黑|银|金|黑|红)[^，；。]{0,3}(?:长发|短发|卷发|头发|发丝|盘发|发)|(?:长卷发|半束长发|盘发)/g) || []
 const knownHair=tags(hair+'；'+proseHair.join('；'),hairColors)
 const knownLength=tags(hair+'；'+proseHair.join('；'),hairLengths)
 const knownEyes=tags(visibleText(info.eyeColor),eyeColors)
 return catalog.filter(a=>a.gender===gender && a.ages.includes(group) && (a.cultures || ['WESTERN']).includes(culture))
  .filter(a=>species && species!=='unknown' ? (a.allowedSpecies || [a.species]).includes(species) : a.species==='human' || ['ELF','ROSE_ELF'].includes(role))
  .map(a=>{
   const roles=a.roles || [a.role]
   const styleMatch=!!(a.style && a.style===style)
   const roleMatch=roles.includes(role) || (!role && roles.some(r=>hits(basis,roleAliases[r] || [])))
   const equipment=a.equipment || (a.outfit || []).filter(t=>/剑|弓|魔杖|旧书|书卷/.test(t))
   const garment=hits(clothing,(a.outfit || []).filter(t=>!equipment.includes(t)))
   const equipmentMatch=hits(clothing,equipment)
   const extra=hits(keywords+'；'+basis,a.keywords)
   const hairMatch=overlap(knownHair,tags(lower((a.hair || []).join('；')),hairColors))
   const lengthMatch=overlap(knownLength,tags(lower((a.hair || []).join('；')),hairLengths))
   const eyesMatch=overlap(knownEyes,tags(lower((a.eyes || []).join('；')),eyeColors))
   const genericElf=a.species==='elf' && species==='elf' && !['ROSE_ELF'].includes(role) && !a.style && !a.exclusiveRole
   const genericDragonborn=a.species==='dragonborn' && species==='dragonborn'
   const genericVampire=a.species==='vampire' && species==='vampire'
   const specialMismatch=(a.style && !styleMatch) || (a.exclusiveRole && role!==a.exclusiveRole) || (role==='ROSE_ELF' && !roles.includes(role))
   // A modern scholar or noble needs an actually visible compatible outfit.
   const modernOutfits={'P261026-01':['黑色晚礼服','黑缎','黑色礼服'],'P261026-04':['墨蓝学院外袍','学院长袍']}
   const modernOk=culture!=='MODERN' || hits(clothing,modernOutfits[a.key] || [])
   const visibleTraits=visibleText([basis,keywords,...list(info.scaleColor),...list(info.wingColor),...list(info.hornColor)])
   const appearanceConflict=hits(visibleTraits,a.appearanceExclusions)
   const signatureOk=!a.selection || a.selection.roles?.includes(role) || hits(clothing,a.selection.keywords)
   const mountOk=a.composition!=='character-and-mount' || mount?.species===a.mount?.species && (!mount?.coat || mount.coat===a.mount?.coat)
   const anatomyOk=prosthesisCompatible(a,info,clothing)
   const eligible=!specialMismatch && !appearanceConflict && modernOk && signatureOk && mountOk && anatomyOk && (styleMatch || roleMatch || garment || genericElf || genericDragonborn || genericVampire)
   const score=(styleMatch?500:0)+(roleMatch?30:0)+(garment?40:0)+(equipmentMatch?12:0)+(genericElf || genericDragonborn || genericVampire?10:0)+(a.composition==='character-and-mount' && mount?24:0)+hairMatch*15+lengthMatch*8+eyesMatch*7+(extra?5:0)+(a.legacy?1:0)
   return {asset:a,eligible,score}
  }).filter(a=>a.eligible).sort((a,b)=>b.score-a.score || a.asset.key.localeCompare(b.asset.key))
}
const hash=value=>Array.from(value).reduce((n,c)=>(Math.imul(n,31)+c.codePointAt(0))>>>0,0).toString(36)
// When no specific illustration fits, rank the remaining public traits instead
// of requiring a profession, exact costume or a signature accessory to exist.
// The result is artwork approximation only, never new facts about the person.
export function rankClosestPortraits(catalog, info, {culture,gender,group,species}) {
 const role=portraitRole(info.archetype),style=String(info.portraitStyle || '').toUpperCase()
 const basis=visibleText(info.basis),keywords=visibleText(info.portraitKeywords)
 const text=visibleText([basis,keywords,...list(info.clothing),...list(info.visibleEquipment)])
 const proseHair=(basis+'；'+keywords).match(/(?:银白|银灰|月白|霜白|灰金|浅金|栗棕|灰褐|棕褐|棕色|乌黑|银|金|黑|红)[^，；。]{0,3}(?:长发|短发|卷发|头发|发丝|盘发|发髻|发)|(?:长卷发|半束长发|盘发|发髻|挽发|束髻)/g) || []
 const hair=visibleText([...list(info.hairColor),...list(info.hairLength),...proseHair])
 const knownHair=tags(hair,hairColors),knownLength=tags(hair,hairLengths),knownEyes=tags(visibleText(info.eyeColor),eyeColors)
 const mount=publicMount(info),ordinary=!species || ['human','unknown','other'].includes(species)
 const ageOrder={YOUNG:0,ADULT:1,ELDER:2}
 let compatible=catalog.filter(a=>a.gender===gender && (a.cultures || ['WESTERN']).includes(culture))
  .filter(a=>group==='CHILD' ? a.ages.includes('CHILD') : a.ages.some(age=>age!=='CHILD'))
  // An unknown ancestry must not reveal a vampire or dragon form through a picture.
  .filter(a=>!ordinary || ['human','humanoid'].includes(a.species))
  .filter(a=>a.composition!=='character-and-mount' || mount?.species===a.mount?.species && (!mount.coat || mount.coat===a.mount?.coat))
  .filter(a=>prosthesisCompatible(a,info,text))
 if(!ordinary){
  const sameSpecies=compatible.filter(a=>(a.allowedSpecies || [a.species]).includes(species))
  compatible=sameSpecies.length?sameSpecies:compatible.filter(a=>['human','humanoid'].includes(a.species))
 }
 return compatible.map(a=>{
   const matchedTags=[`题材:${culture}`,`性别:${gender}`]
   let score=0
   const add=(condition,label,weight)=>{if(condition){matchedTags.push(label);score+=weight}}
   const roles=a.roles || [a.role]
   const roleMatch=roles.includes(role) || (!role && roles.some(r=>hits(basis,roleAliases[r] || [])))
   const speciesMatch=(a.allowedSpecies || [a.species]).includes(species)
   add(speciesMatch,`种族:${species}`,30)
   if(!ordinary && !speciesMatch)score-=30
   add(a.ages.includes(group),`年龄:${group}`,12)
   if(!a.ages.includes(group) && group in ageOrder){
    const distances=a.ages.filter(age=>age in ageOrder).map(age=>Math.abs(ageOrder[age]-ageOrder[group]))
    if(distances.length)score-=Math.min(...distances)*12
   }
   add(roleMatch,`职业:${role || roles.find(r=>hits(basis,roleAliases[r] || []))}`,20)
   add(a.style && a.style===style,`风格:${style}`,25)
   const assetHair=lower((a.hair || []).join('；')),assetEyes=lower((a.eyes || []).join('；'))
   for(const color of knownHair)add(tags(assetHair,hairColors).includes(color),`发色:${color}`,15)
   for(const length of knownLength)add(tags(assetHair,hairLengths).includes(length),`发型:${length}`,12)
   for(const color of knownEyes)add(tags(assetEyes,eyeColors).includes(color),`眼睛:${color}`,6)
   const garment=[...new Set([...(a.outfit || []),...(a.equipment || [])])].filter(token=>hits(text,[token]))
   for(const token of garment.slice(0,6))add(true,`衣装装备:${token}`,6)
   const extra=[...new Set(a.keywords || [])].filter(token=>hits(basis+'；'+keywords,[token]))
   for(const token of extra.slice(0,6))add(true,`特征:${token}`,2)
   if(overlap(knownHair,tags(assetHair,hairColors))<0)score-=10
   if(overlap(knownLength,tags(assetHair,hairLengths))<0)score-=6
   if(a.selection && !a.selection.roles?.includes(role) && !hits(text,a.selection.keywords))score-=20
   if(a.style && a.style!==style)score-=8
   if(a.exclusiveRole && a.exclusiveRole!==role)score-=12
   if(hits(visibleText([basis,keywords,...list(info.scaleColor),...list(info.wingColor),...list(info.hornColor)]),a.appearanceExclusions))score-=40
   return {asset:a,score,matchedTags}
  }).sort((a,b)=>b.score-a.score || b.matchedTags.length-a.matchedTags.length || Number(!!b.asset.approved)-Number(!!a.asset.approved) || a.asset.key.localeCompare(b.asset.key))
}
export function stablePortrait(ranked, person, scope, context) {
 if(!ranked.length)return null
 const id=person.id || person.godId
 if(!id)return ranked[0].asset
 // Different visible evidence and perspectives have separate records. Reading
 // an older save never inherits a later disguise or a hidden omniscient form.
 const evidence=JSON.stringify([context,ranked.map(r=>[r.asset.key,r.score])])
 const key='bs:portrait:v3:'+scope+':'+id+':'+hash(evidence)
 try {
  const stored=JSON.parse(globalThis.localStorage?.getItem(key) || 'null')
  const prior=stored?.evidence===evidence && ranked.find(r=>r.asset.key===stored.portraitAssetId)
  if(prior)return prior.asset
  const storage=globalThis.localStorage
  if(storage) {
   const records=Object.keys(storage).filter(k=>k.startsWith('bs:portrait:v3:'))
   if(records.length>=256)storage.removeItem(records[0])
   storage.setItem(key,JSON.stringify({portraitAssetId:ranked[0].asset.key,evidence}))
  }
 } catch { /* Storage denial never interrupts reading or story generation. */ }
 return ranked[0].asset
}
