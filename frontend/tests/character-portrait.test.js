import {describe,it,expect,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {characterPortrait,sceneCast} from '../src/utils/characterPortrait'
import Stage from '../src/components/CharacterStage.vue'
const person=(name,introduction,id=name)=>({id,name,introduction})
describe('public character artwork mapping',()=>{
 it('covers the requested types and keeps the two young variants stable',()=>{
  expect(characterPortrait(person('阿禾','少女阿禾捏着信封。')).key).toMatch(/^young-woman-/)
  expect(characterPortrait(person('阿禾','少女阿禾捏着信封。')).key).toBe(characterPortrait(person('改名','改名是一位少女。','阿禾')).key)
  expect(characterPortrait(person('芸娘','芸娘是位妇人。')).key).toBe('woman')
  expect(characterPortrait(person('陈翁','老翁陈翁倚杖而立。')).key).toBe('elder')
  expect(characterPortrait(person('陈婆','老妇陈婆坐在窗边。')).key).toBe('elder-woman')
  expect(characterPortrait(person('小梨','女童小梨望着河面。')).key).toBe('child-girl')
  expect(characterPortrait(person('山君','山君是神明。'))).toMatchObject({symbolic:true,matchKind:'symbolic'})
 })
 it('does not leak secret identities or infer type from a name, status or private dossier',()=>{
  const p={id:'1',name:'女神',godStatus:'神明',appearance:{isDivine:true},dossier:{identity:{age:8,gender:'女'}},psychology:{trueIdentity:'神明'}}
  expect(characterPortrait(p)).toMatchObject({symbolic:true,gender:'unknown',ageGroup:'ADULT'})
  expect(characterPortrait(p,'OMNISCIENT').symbolic).toBe(true)
  expect(characterPortrait({...p,introduction:'女神不是神明。'}).src).toBe(characterPortrait(p).src)
  expect(characterPortrait({...p,introduction:'据说女神是神明。'}).src).toBe(characterPortrait(p).src)
 })
 it('does not mistake another person in the same sentence for this character',()=>{
  expect(characterPortrait(person('阿禾','少女阿禾牵着女童小梨。')).key).toMatch(/^young-woman-/)
  expect(characterPortrait(person('小梨','少女阿禾牵着女童小梨。')).key).toBe('child-girl')
  expect(characterPortrait(person('芸娘','芸娘是女童小梨的母亲。'))).toMatchObject({symbolic:true,ageGroup:'ADULT',gender:'unknown'})
  expect(characterPortrait(person('无名','无名提到了少女阿禾。'))).toMatchObject({symbolic:true,ageGroup:'ADULT',gender:'unknown'})
 })
 it('respects public age and gender, and never uses the female child for a boy',()=>{
  expect(characterPortrait({id:'f',publicAppearance:{age:8,gender:'female'}}).key).toBe('child-girl')
  expect(characterPortrait({id:'m',publicAppearance:{age:8,gender:'male'}})).toMatchObject({symbolic:true,ageGroup:'CHILD',gender:'male',portraitAssetId:'COMMON-03'})
  expect(characterPortrait({id:'f',publicAppearance:{age:70,gender:'female'}}).key).toBe('elder-woman')
  expect(characterPortrait({id:'m',publicAppearance:{age:35,gender:'male'}}).key).toBe('man')
  expect(characterPortrait({id:'f',publicAppearance:{age:35,gender:'female'}}).src).toContain('/eastern-woman.png')
  expect(characterPortrait(person('叶青','叶青是一位18岁女子。')).key).toMatch(/^young-woman-/)
 })
 it('matches opening introductions by character ID rather than array position',()=>{
  const cast=sceneCast({presentGods:[{godId:'a',godName:'阿禾'},{godId:'b',godName:'小梨'}],openingCharacters:[{id:'b',introduction:'女童小梨望着河面。'},{id:'a',introduction:'少女阿禾站在桥边。'}]})
  expect(characterPortrait(cast[0]).key).toMatch(/^young-woman-/);expect(characterPortrait(cast[1]).key).toBe('child-girl')
 })
 it('prefers the latest public appearance to an obsolete opening description',()=>{
  expect(characterPortrait({...person('行者','少女行者站在河边。'),publicAppearance:{gender:'male',ageGroup:'ELDER'}}).key).toBe('elder')
  expect(characterPortrait({...person('小梨','女童小梨站在河边。'),publicAppearance:{gender:'female',ageGroup:'ADULT'}}).key).toBe('woman')
 })
})
describe('paper stage interactions do not execute game actions',()=>{
 const scene={sceneId:'river',presentGods:[{godId:'a',godName:'阿禾'},{godId:'b',godName:'小梨'}],openingCharacters:[{id:'a',introduction:'少女阿禾站在桥边。'},{id:'b',introduction:'女童小梨望着河面。'}]}
 it('switches artwork, drafts talk for that exact role and clears selection on branch change',async()=>{
  const page=mount(Stage,{props:{scene,identity:'A:0:DREAMER',viewMode:'DREAMER',atmosphere:{key:'water',label:'水岸'}}})
  await page.findAll('.stage-cast-list button').at(-1).trigger('click')
  expect(page.find('.stage-caption h2').text()).toBe('小梨')
  expect(page.find('.paper-person').attributes('src')).toContain('child-girl')
  await page.findAll('.stage-caption-actions button')[1].trigger('click')
  expect(page.emitted('talk')[0][0].godId).toBe('b')
  await page.setProps({identity:'A:1:DREAMER'})
  expect(page.find('.stage-caption h2').text()).toBe('阿禾');page.unmount()
 })
 it('preserves reading when artwork fails and supports empty old saves',async()=>{
  const page=mount(Stage,{props:{scene,atmosphere:{key:'forest',label:'林间'}}})
  await page.find('.paper-person').trigger('error');expect(page.get('.paper-person').attributes('src')).toContain('/portraits-common-v1/');expect(page.text()).toContain('剪影')
  await page.setProps({scene:{}});expect(page.find('.stage-caption h2').text()).toBe('此刻独行');expect(page.find('.paper-person').exists()).toBe(true);page.unmount()
 })
})
