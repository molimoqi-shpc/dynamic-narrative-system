import {describe,it,expect,afterEach} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {readFileSync,existsSync} from 'node:fs'
import {resolve} from 'node:path'
import {characterPortrait,portraitCatalog,sceneCast} from '../src/utils/characterPortrait'
import {symbolicPortrait,commonPortraits} from '../src/utils/portraitFallback'
import {portraitLibrary} from '../src/utils/portraitLibrary'
import Stage from '../src/components/CharacterStage.vue'

const views=[]
afterEach(()=>views.splice(0).forEach(view=>view.unmount()))
const render=scene=>{const view=mount(Stage,{props:{scene,identity:'visual-only',viewMode:'DREAMER',atmosphere:{key:'default',label:'旅途'}}});views.push(view);return view}
const person=(id,appearance={})=>({godId:id,godName:'旅人'+id,publicAppearance:appearance})

describe('a populated portrait stage without invented character facts',()=>{
 it('has an image with no keyword overlap across every supported world, sex and age group',()=>{
  expect(()=>characterPortrait({id:'old-save',name:'旧档来客'},'DREAMER',null)).not.toThrow()
  expect(characterPortrait({id:'old-save',name:'旧档来客'},'DREAMER',null).src).toBeTruthy()
  for(const culture of ['WESTERN','EASTERN','MODERN','SCIFI','MIXED'])for(const gender of ['female','male','unknown'])for(const ageGroup of ['CHILD','ADULT','ELDER']){
   const p={id:'unmatched-'+gender+ageGroup,name:'未知来客',publicAppearance:{culturalStyle:culture,gender,ageGroup,archetype:'UNLISTED_OCCUPATION',portraitKeywords:[]}}
   const before=JSON.stringify(p),art=characterPortrait(p,'DREAMER',{culturalStyle:culture})
   expect(art.src,`${culture}/${gender}/${ageGroup}`).toBeTruthy();expect(JSON.stringify(p)).toBe(before)
   if(art.symbolic){expect(art.culture).toBe(culture);expect(art.ageGroup).toBe(ageGroup);expect(art.portraitAssetId==='COMMON-03').toBe(ageGroup==='CHILD')}
   else if(portraitCatalog.some(asset=>asset.key===art.key)){const asset=portraitCatalog.find(asset=>asset.key===art.key);expect(asset.gender).toBe(gender);expect(asset.cultures || ['WESTERN']).toContain(culture)}
  }
 })
 it('does not reveal gender, ancestry or child identity from hidden context or a suggestive name',()=>{
  const p={id:'same-person',name:'银发精灵公主',appearance:{gender:'female',species:'elf',apparentAge:10},dossier:{identity:{isDivine:true}}}
  const art=characterPortrait(p,'DREAMER',{culturalStyle:'MODERN'})
  expect(art).toMatchObject({symbolic:true,gender:'unknown',ageGroup:'ADULT',culture:'MODERN'})
  expect(art.src).toBe(characterPortrait({id:p.id,name:'另一称呼'},'DREAMER',{culturalStyle:'MODERN'}).src)
  expect(characterPortrait(p,'OMNISCIENT',{culturalStyle:'MODERN'})).toMatchObject({symbolic:true,ageGroup:'CHILD',portraitAssetId:'COMMON-03'})
 })
 it('keeps anonymous poses stable and supplies distinct poses without storage or model calls',()=>{
  const before=globalThis.fetch.mock.calls.length
  const art=symbolicPortrait({id:'traveler-A'},'DREAMER',{culturalStyle:'WESTERN'})
  expect(symbolicPortrait({id:'traveler-A'},'DREAMER',{culturalStyle:'WESTERN'}).src).toBe(art.src)
  const variants=new Set(Array.from({length:12},(_,i)=>symbolicPortrait({id:'traveler-'+i},'DREAMER',{culturalStyle:'WESTERN'}).src))
  expect(variants.size).toBe(2);expect(globalThis.fetch.mock.calls.length).toBe(before)
 })
 it('uses generated transparent assets with searchable labels in the real atlas',()=>{
  expect(commonPortraits).toHaveLength(3)
  for(const asset of commonPortraits){const file=resolve('public'+asset.src);expect(existsSync(file)).toBe(true);const bytes=readFileSync(file);expect([...bytes.subarray(0,8)]).toEqual([137,80,78,71,13,10,26,10]);expect(bytes.readUInt32BE(16)).toBe(1024);expect(bytes.readUInt32BE(20)).toBe(1536);expect(bytes[25]).toBe(6);expect(asset.keywords.length).toBeGreaterThanOrEqual(12);expect(portraitLibrary.find(item=>item.key===asset.key)?.src).toBe(asset.src)}
 })
 it('replaces a broken portrait, then survives even the fallback file failing without a retry loop',async()=>{
  const scene={sceneId:'station',worldSetting:{culturalStyle:'WESTERN'},presentGods:[person('elf',{gender:'female',species:'elf',ageGroup:'ADULT'})]}
  const original=JSON.stringify(scene),before=globalThis.fetch.mock.calls.length,view=render(scene)
  expect(view.get('img.paper-person').attributes('src')).toContain('western-elf-female')
  await view.get('img.paper-person').trigger('error')
  expect(view.get('img.paper-person').attributes('src')).toContain('/portraits-common-v1/')
  await view.get('img.paper-person').trigger('error');await flushPromises()
  expect(view.find('img.paper-person').exists()).toBe(false);expect(view.find('.paper-person svg').exists()).toBe(true);expect(view.find('.portrait-silhouette svg').exists()).toBe(true)
  expect(view.text()).not.toContain('形象尚未记明');expect(view.emitted('talk')).toBeUndefined();expect(JSON.stringify(scene)).toBe(original);expect(globalThis.fetch.mock.calls.length).toBe(before)
  await view.setProps({scene:{...scene,presentGods:[person('other',{gender:'female',species:'elf',ageGroup:'ADULT'})]}})
  expect(view.find('img.paper-person').exists()).toBe(true);expect(view.get('.paper-person').attributes('src')).toContain('western-elf-female')
 })
 it('illustrates a solo passage without adding an NPC or enabling talk',()=>{
  const scene={sceneId:'alone',worldSetting:{culturalStyle:'MODERN'},presentGods:[]},before=JSON.stringify(scene),view=render(scene)
  expect(view.get('.paper-person').attributes('src')).toContain('/portraits-common-v1/');expect(view.get('.stage-caption h2').text()).toBe('此刻独行')
  expect(view.findAll('.stage-caption-actions button')).toHaveLength(1);expect(view.find('.stage-cast-menu').exists()).toBe(false);expect(sceneCast(scene)).toEqual([]);expect(JSON.stringify(scene)).toBe(before)
 })
})
