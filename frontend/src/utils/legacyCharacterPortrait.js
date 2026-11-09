// Presentation only. Never inspect private psychology, infer gender from names,
// or turn a picture choice into a rule for story generation.
const root='/art/paper-theatre-v1/'
export const portraitTypes={
 'young-woman-a':'年轻女子 · 青衣', 'young-woman-b':'年轻女子 · 朱衣',
 woman:'成年女子', elder:'老年男子', 'elder-woman':'老年女子',
 deity:'神明', 'child-girl':'女孩', man:'成年男子'
}
const hash=value=>Array.from(String(value)).reduce((n,c)=>(Math.imul(n,31)+c.codePointAt(0))>>>0,0)
function ownDescription(person){
 const name=String(person.name || person.godName || '')
 if(!name)return ''
 // Opening excerpts can mention several people. Only use the clause actually
 // introducing this person, never the age of a child mentioned by their parent.
 const clauses=String(person.introduction || '').split(/[，,。；;！？\n]/)
 const escape=name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
 const kind='(?:年轻女子|成年女子|少女|女子|妇人|妇女|女童|女孩|幼女|老妇|老妪|婆婆|老者|老翁|老人|男子|男人|神明|神祇|神灵)'
 const before=new RegExp('('+kind+')(?:名叫|名为|唤作|叫|[“”\\s])*'+escape)
 const after=new RegExp('^\\s*'+escape+'(?:看上去是|是一位|是一名|是个|是|乃|为|[（(：:])([^，。；]{0,24})')
 const out=[]
 for(const clause of clauses){
  if(/据说|传闻|听说|可能|仿佛|不是|并非/.test(clause))continue
  const prefix=clause.match(before);if(prefix)out.push(prefix[1])
  const suffix=clause.match(after)?.[1]
  // “阿蘅是女童阿梨的母亲” describes a relationship, not a child.
  if(suffix && !suffix.includes('的')){const type=suffix.match(new RegExp(kind));if(type)out.push(type[0]);const age=suffix.match(/\b(\d{1,3})岁/);if(age)out.push(age[0])}
 }
 return out.join('；')
}
export function characterPortrait(person={},scope='DREAMER'){
 const id=person.id || person.godId || ''
 // publicAppearance/known.appearance are explicitly public display metadata.
 // An unconfirmed divine identity must never leak through the asset selection.
 const publicInfo=person.publicAppearance || person.known?.appearance || {}
 const info=scope==='OMNISCIENT' ? (person.appearance || publicInfo) : publicInfo
 const text=ownDescription(person)
 const gender=String(info.gender || '').toLowerCase()
 const hasGender=['female','女','女性','male','男','男性'].includes(gender)
 const female=hasGender?['female','女','女性'].includes(gender):/少女|女子|妇人|妇女|女童|女孩|幼女|老妇|老妪|婆婆/.test(text)
 const male=hasGender?['male','男','男性'].includes(gender):/男子|男人|老翁|老爷爷/.test(text)
 const age=typeof info.age==='number' && info.age>=0 ? info.age : text.match(/(\d{1,3})岁/) ? Number(text.match(/(\d{1,3})岁/)[1]) : null
 const hasAgeGroup=['CHILD','YOUNG','ADULT','ELDER'].includes(info.ageGroup)
 const child=hasAgeGroup?info.ageGroup==='CHILD':age!==null?age<14:/女童|女孩|幼女/.test(text)
 const old=hasAgeGroup?info.ageGroup==='ELDER':age!==null?age>=60:/老妇|老妪|婆婆|老者|老翁|老人/.test(text)
 const divine=typeof info.divineRevealed==='boolean'?info.divineRevealed:/神明|神祇|神灵/.test(text)
 let key=null
 if(divine || (scope==='OMNISCIENT' && info.isDivine===true))key='deity'
 else if(child)key=female?'child-girl':null
 else if(old)key=female?'elder-woman':male || /老者|老翁/.test(text)?'elder':null
 else if(female){
  const young=hasAgeGroup?info.ageGroup==='YOUNG':age!==null?age<30:/少女|年轻女子/.test(text)
  key=young?(hash(id)%2?'young-woman-a':'young-woman-b'):'woman'
 }else if(male && !child)key='man'
 return {key,src:key?`${root}${key==='woman'?'woman-v2':key}.png`:null,label:key?portraitTypes[key]:'形象尚未记明'}
}
export function sceneCast(scene){
 return (scene.presentGods || []).filter(p=>p?.godId && p?.godName).map(p=>{
  const opening=(scene.openingCharacters || []).find(c=>c.id===p.godId)
  return {...p,id:p.godId,name:p.godName,introduction:opening?.introduction || '',publicAppearance:p.publicAppearance}
 })
}
