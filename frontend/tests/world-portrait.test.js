import {describe,it,expect} from 'vitest'
import {characterPortrait,sceneCast,portraitStage,portraitCatalog} from '../src/utils/characterPortrait'
const west={culturalStyle:'WESTERN'},east={culturalStyle:'EASTERN'}
const p=(gender='female',extra={})=>({id:'stable',publicAppearance:{gender,ageGroup:'ADULT',culturalStyle:'WESTERN',...extra}})
function closest(result,gender='female',culture='WESTERN'){
 expect(result.src).toBeTruthy();expect(result.matchKind).toBe('closest')
 const asset=portraitCatalog.find(item=>item.key===result.key)
 expect(asset.gender).toBe(gender);expect(asset.cultures||['WESTERN']).toContain(culture)
 return asset
}
describe('world-aware portrait compatibility',()=>{
 it('keeps rose elf distinct and uses it only for the explicit public appearance',()=>{
  const rose=characterPortrait(p('female',{species:'elf',archetype:'ROSE_ELF'}),'DREAMER',west)
  expect(rose.key).toBe('western-rose-elf-female')
  expect(rose.src).toContain('/refraction-v2/')
  expect(characterPortrait(p('female',{species:'elf',archetype:'ELF'}),'DREAMER',west).key).toBe('western-elf-female')
  expect(closest(characterPortrait(p('male',{species:'elf',archetype:'ROSE_ELF'}),'DREAMER',west),'male').species).toBe('elf')
  expect(characterPortrait(p('female',{species:'elf',archetype:'ROSE_ELF'}),'DREAMER',east).symbolic).toBe(true)
 })
 it('pairs the snow cliff with the knight without moving other portraits into that environment',()=>{
  const knight=characterPortrait(p('male',{species:'human',archetype:'KNIGHT'}),'DREAMER',west)
  expect(portraitStage(knight).backdrop).toBe('/art/refraction-v2/stage-knight-night.png')
  expect(portraitStage(characterPortrait(p('female',{species:'elf'}),'DREAMER',west)).backdrop).toBeUndefined()
  expect(portraitStage(characterPortrait(p('male',{archetype:'KNIGHT'}),'DREAMER',east)).backdrop).toBeUndefined()
 })
 it('uses a compatible approximation when exact species or role art is unavailable without changing identity',()=>{
  const dwarf=p('male',{species:'dwarf',archetype:'KNIGHT'}),before=JSON.stringify(dwarf)
  closest(characterPortrait(dwarf,'DREAMER',west),'male')
  expect(JSON.stringify(dwarf)).toBe(before)
  expect(closest(characterPortrait(p('female',{species:'human',archetype:'ELF'}),'DREAMER',west)).species).toBe('human')
 })
 it('never lets a western asset cross into an eastern world',()=>{
  expect(characterPortrait(p('female',{archetype:'ELF',species:'elf'}),'DREAMER',east).symbolic).toBe(true)
  expect(characterPortrait({...p(),worldStyle:'WESTERN',publicAppearance:{gender:'male',ageGroup:'ADULT',culturalStyle:'EASTERN'}}).symbolic).toBe(true)
 })
 it('uses sex, age and public role together; no female knight fallback for men',()=>{
  expect(characterPortrait(p('male',{archetype:'KNIGHT'}),'DREAMER',west).key).toBe('western-knight-male')
  expect(characterPortrait(p('female',{archetype:'KNIGHT'}),'DREAMER',west).key).toBe('P261026-03')
  closest(characterPortrait(p('male',{archetype:'NOBLE'}),'DREAMER',west),'male')
  expect(characterPortrait(p('female',{archetype:'NOBLE'}),'DREAMER',west).key).toBe('western-noble-female')
 })
 it('does not render children as adult models or younger clerics as elderly pontiffs',()=>{
  expect(characterPortrait(p('female',{ageGroup:'CHILD',archetype:'ELF'}),'DREAMER',west).symbolic).toBe(true)
  expect(closest(characterPortrait(p('male',{ageGroup:'ADULT',archetype:'CLERIC'}),'DREAMER',west),'male').ages).toContain('ADULT')
  expect(characterPortrait(p('male',{ageGroup:'ELDER',archetype:'CLERIC'}),'DREAMER',west).key).toBe('western-cleric-male')
 })
 it('does not guess female, elf or culture from a name or private psychology',()=>{
  const unknown={id:'x',name:'女精灵公主',dossier:{identity:{gender:'female',species:'elf'}},worldStyle:'WESTERN'}
  expect(characterPortrait(unknown).symbolic).toBe(true)
  expect(characterPortrait({...p('unknown'),publicAppearance:{gender:'unknown',ageGroup:'ADULT',archetype:'ELF',culturalStyle:'WESTERN'}}).symbolic).toBe(true)
 })
 it('uses modern-compatible fallback artwork but does not invent an unsupported science-fiction catalogue',()=>{
  closest(characterPortrait(p('female',{archetype:'NOBLE',culturalStyle:'MODERN'}),'DREAMER',{culturalStyle:'MODERN'}),'female','MODERN')
  expect(characterPortrait(p('female',{archetype:'NOBLE',culturalStyle:'SCIFI'}),'DREAMER',{culturalStyle:'SCIFI'}).symbolic).toBe(true)
 })
 it('matches both elf identity and rider occupation without leaking private forms',()=>{
  expect(characterPortrait(p('female',{species:'elf'}),'DREAMER',west).key).toBe('western-elf-female')
  expect(characterPortrait(p('male',{archetype:'DRAGON_RIDER'}),'DREAMER',west).key).toBe('western-dragon-rider-male')
  expect(characterPortrait({...p('male',{archetype:'KNIGHT'}),appearance:{gender:'female',species:'elf'}},'DREAMER',west).key).toBe('western-knight-male')
 })
 it('keeps the same known identity stable across renamed characters and scene ordering',()=>{
  const a=p('female',{species:'elf'}),a2={...a,name:'改用公开别称'}
  expect(characterPortrait(a,'DREAMER',west).src).toBe(characterPortrait(a2,'DREAMER',west).src)
  const cast=sceneCast({worldSetting:west,presentGods:[{godId:'stable',godName:'莉安',publicAppearance:a.publicAppearance}],openingCharacters:[]})
  expect(characterPortrait(cast[0]).key).toBe('western-elf-female')
 })
})

