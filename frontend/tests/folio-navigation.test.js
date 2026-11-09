import {describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createRouter,createMemoryHistory} from 'vue-router'
import Stage from '../src/components/CharacterStage.vue'
import JourneyMenu from '../src/components/JourneyMenu.vue'
import {durationText,storyMoment} from '../src/utils/storyTime'

describe('readable recorded moments',()=>{
 it('uses the established world label at the same second without inventing a calendar',()=>{
  expect(storyMoment(18000,{currentSeconds:18000,currentLabel:'初秋薄暮'})).toBe('初秋薄暮')
  expect(storyMoment(17700,{currentSeconds:18000,currentLabel:'初秋薄暮'})).toBe('5分钟前')
  expect(storyMoment(18000)).toBe('启程后5小时')
  expect(storyMoment(18000,{recordedLabel:'第七日夜里'})).toBe('第七日夜里')
 })
 it('handles zero, unknown, long durations and timestamps ahead of the displayed scene',()=>{
  expect(storyMoment(0)).toBe('旅程起始');expect(storyMoment(null)).toBe('时刻未记录')
  expect(storyMoment(18000,{currentSeconds:100,currentLabel:'旧时刻'})).toBe('启程后5小时')
  expect(durationText(90061)).toBe('1天1小时');expect(durationText(NaN)).toBe('时长未记录')
 })
})
const appearance={gender:'female',ageGroup:'ADULT',culturalStyle:'WESTERN',species:'elf',archetype:'ELF'}
const scene={sceneId:'station',worldSetting:{culturalStyle:'WESTERN'},presentGods:[{godId:'a',godName:'莉安',publicAppearance:appearance},{godId:'b',godName:'伊芙',publicAppearance:appearance}]}
describe('portrait change stability',()=>{
 it('reuses the same portrait DOM for two elves while talk still targets the selected identity',async()=>{
  const view=mount(Stage,{props:{scene,atmosphere:{key:'temple'},viewMode:'DREAMER'}})
  const image=view.get('.paper-person').element
  await view.get('[aria-label="下一位人物"]').trigger('click');await flushPromises()
  expect(view.get('.stage-caption h2').text()).toBe('伊芙');expect(view.get('.paper-person').element).toBe(image)
  await view.findAll('.stage-caption-actions button')[1].trigger('click');expect(view.emitted('talk')[0][0].godId).toBe('b');view.unmount()
 })
 it('keeps the current image while decoding and rejects a delayed switch after changing branches',async()=>{
  const pending=new Map()
  vi.stubGlobal('Image',class{set src(value){this.url=value}decode(){return new Promise(resolve=>pending.set(this.url,resolve))}})
  try{
   const second={...scene.presentGods[1],publicAppearance:{gender:'female',ageGroup:'ADULT',culturalStyle:'WESTERN',species:'human',archetype:'NOBLE'}}
   const view=mount(Stage,{props:{scene:{...scene,presentGods:[scene.presentGods[0],second]},identity:'A:0',atmosphere:{key:'temple'},viewMode:'DREAMER'}})
   await view.get('[aria-label="下一位人物"]').trigger('click')
   expect(view.get('.stage-caption h2').text()).toBe('莉安');expect(view.get('.paper-person').attributes('src')).toContain('western-elf')
   await view.setProps({identity:'A:1'});for(const resolve of pending.values())resolve();await flushPromises()
   expect(view.get('.stage-caption h2').text()).toBe('莉安');view.unmount()
  }finally{vi.unstubAllGlobals()}
 })
})
it('leaves for the archive without cancelling an unfinished server operation',async()=>{
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/play',component:{template:'<div />'}},{path:'/home',component:{template:'<div />'}}]})
 await router.push('/play');await router.isReady()
 const view=mount(JourneyMenu,{props:{pending:true},global:{plugins:[router]}})
 expect(view.text()).toContain('服务器处理');await view.findAll('button')[0].trigger('click');await flushPromises()
 expect(router.currentRoute.value.fullPath).toBe('/home?archives=1');view.unmount()
})
