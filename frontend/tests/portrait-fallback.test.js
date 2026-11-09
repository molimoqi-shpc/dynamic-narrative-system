import {beforeEach,describe,it,expect} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import {rankClosestPortraits} from '../src/utils/portraitMatching'
import CharacterStage from '../src/components/CharacterStage.vue'

const west={culturalStyle:'WESTERN'}
const mirrorMaker=(id='elsha',extra={})=>({
 id,name:'伊尔莎·瓦斯克',
 publicAppearance:{gender:'female',age:51,ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',
  archetype:'MIRROR_MAKER',hairColor:'灰褐',hairLength:'发髻',
  basis:'五十一岁，灰褐色头发挽起，眼白发浊，披一件旧羊毛披肩，掌中托着一块镜片。',...extra}
})
const draw=person=>characterPortrait(person,'DREAMER',west)
beforeEach(()=>localStorage.clear())

describe('closest public portrait fallback',()=>{
 it('gives the middle-aged western mirror maker an existing same-sex and same-world illustration',()=>{
  const portrait=draw(mirrorMaker())
  expect(portrait.src).toBeTruthy()
  const asset=portraitCatalog.find(candidate=>candidate.key===portrait.key)
  expect(asset).toBeTruthy()
  expect(asset.gender).toBe('female')
  expect(asset.cultures||['WESTERN']).toContain('WESTERN')
  expect(asset.ages).not.toEqual(['CHILD'])
  expect(asset.composition).not.toBe('character-and-mount')
  expect(asset.requiredProsthesis).toBeFalsy()
 })

 it('ranks more matching public tags first when the exact occupation is unavailable',()=>{
  const asset=(key,extra)=>({key,gender:'female',ages:['ADULT'],cultures:['WESTERN'],species:'human',
   roles:['SCHOLAR'],hair:[],eyes:[],outfit:[],equipment:[],keywords:[],...extra})
  const catalog=[
   asset('hair-only',{hair:['brown','棕色','长发']}),
   asset('different',{hair:['blonde','金发','长发'],eyes:['blue'],outfit:['黑色晚礼服']}),
   asset('closest',{hair:['brown','灰褐','盘发','发髻'],eyes:['amber','琥珀'],outfit:['深棕皮外套'],keywords:['掌镜匠']})
  ]
  const input={archetype:'MIRROR_MAKER',hairColor:'灰褐',hairLength:'发髻',eyeColor:'琥珀',
   clothing:'深棕皮外套',basis:'掌镜匠身穿深棕皮外套，灰褐头发挽成发髻。'}
  const context={culture:'WESTERN',gender:'female',group:'ADULT',species:'human'}
  const ranked=rankClosestPortraits(catalog,input,context)
  expect(ranked[0].asset.key).toBe('closest')
  expect(ranked[0].score).toBeGreaterThan(ranked.find(candidate=>candidate.asset.key==='hair-only').score)
  expect(ranked[0].matchedTags.length).toBeGreaterThan(0)
  expect(rankClosestPortraits([...catalog].reverse(),input,context)[0].asset.key).toBe('closest')
 })

 it('keeps the same public description stable and permits different people to share the best illustration',()=>{
  const original=draw(mirrorMaker())
  expect(draw(mirrorMaker()).src).toBe(original.src)
  expect(draw(mirrorMaker('another-mirror-maker')).src).toBe(original.src)
  localStorage.clear()
  expect(draw(mirrorMaker()).src).toBe(original.src)
 })

 it('does not use private identity or invent a person in an empty scene',()=>{
  const visible=mirrorMaker()
  const privateVersion={...visible,name:'秘密女王',appearance:{gender:'male',species:'vampire',archetype:'DARK_KNIGHT',ageGroup:'ADULT'},
   dossier:{identity:{gender:'male',species:'vampire'}},psychology:{trueIdentity:'吸血鬼骑士'}}
  expect(draw(privateVersion).src).toBe(draw(visible).src)
  expect(characterPortrait()).toMatchObject({src:null})
  expect(characterPortrait({},'DREAMER',west).src).toBeNull()
  expect(characterPortrait({id:'hidden',name:'女精灵',appearance:{gender:'female',species:'elf'}},'DREAMER',west)).toMatchObject({symbolic:true,gender:'unknown'})
  expect(draw(mirrorMaker('unknown',{gender:'unknown'}))).toMatchObject({symbolic:true,gender:'unknown'})
 })

 it('keeps sex, world and child compatibility when ranking nearby assets',()=>{
  const common={ages:['ADULT'],species:'human',hair:['brown'],roles:['ARTIFICER'],keywords:['掌镜匠']}
  const catalog=[
   {...common,key:'same-world-woman',gender:'female',cultures:['WESTERN']},
   {...common,key:'other-world',gender:'female',cultures:['EASTERN']},
   {...common,key:'man',gender:'male',cultures:['WESTERN']},
   {...common,key:'child',gender:'female',ages:['CHILD'],cultures:['WESTERN']}
  ]
  const context={culture:'WESTERN',gender:'female',group:'ADULT',species:'human'}
  expect(rankClosestPortraits(catalog,mirrorMaker().publicAppearance,context).map(candidate=>candidate.asset.key)).toEqual(['same-world-woman'])
 })

 it('uses the fallback consistently for the stage, silhouette and cast thumbnails without a game action',async()=>{
  const cast=[mirrorMaker(),mirrorMaker('hoen',{gender:'male',age:53,archetype:'MIRROR_GRINDER'})]
  cast[1].name='霍恩·瓦斯克'
  const scene={sceneId:'mirror-workshop',worldSetting:west,presentGods:cast.map(person=>({...person,godId:person.id,godName:person.name}))}
  const original=JSON.stringify(scene),networkCount=globalThis.fetch.mock.calls.length
  const stage=mount(CharacterStage,{props:{scene,viewMode:'DREAMER',identity:'portrait-fallback',atmosphere:{key:'default',label:'盐霜下巷'}}})
  try{
   expect(stage.find('.paper-unknown').exists()).toBe(false)
   expect(stage.get('.paper-person').attributes('src')).toBe(draw(cast[0]).src)
   expect(stage.get('.portrait-silhouette').attributes('src')).toBe(draw(cast[0]).src)
   expect(stage.findAll('.cast-portrait-crop img').map(image=>image.attributes('src'))).toEqual(cast.map(person=>draw(person).src))
   await stage.findAll('.stage-cast-list button')[1].trigger('click');await flushPromises()
   expect(stage.get('.paper-person').attributes('src')).toBe(draw(cast[1]).src)
   expect(stage.get('.portrait-silhouette').attributes('src')).toBe(draw(cast[1]).src)
   expect(stage.get('.stage-caption h2').text()).toBe('霍恩·瓦斯克')
   expect(stage.emitted('talk')).toBeUndefined()
   expect(globalThis.fetch.mock.calls.length).toBe(networkCount)
   expect(JSON.stringify(scene)).toBe(original)
  }finally{stage.unmount()}
 })
})
