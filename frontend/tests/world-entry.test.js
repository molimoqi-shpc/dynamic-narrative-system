import {beforeEach,describe,it,expect,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {shallowMount,flushPromises} from '@vue/test-utils'
import {useSessionStore} from '../src/store/session'
const mock=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),replace:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mock.get,post:mock.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({replace:mock.replace,push:vi.fn()})}))
import Creation from '../src/views/CharacterCreationView.vue'
const world={title:'灰港',genre:'幻想',era:'早期工业',introduction:'这个世界有固定的时代和生活。',summary:'既有世界观。',culturalStyle:'WESTERN'}
const ready={sessionId:'new',status:'QUESTIONS',worldSetting:world,questions:['姓名与出身？','重视什么？','怎样面对困境？']}
let pinia,store
beforeEach(()=>{sessionStorage.clear();localStorage.clear();pinia=createPinia();setActivePinia(pinia);store=useSessionStore();store.setActiveSession({playerId:'u',sessionId:null,status:null});mock.get.mockReset();mock.post.mockReset()})
const mountPage=()=>shallowMount(Creation,{global:{plugins:[pinia],stubs:{AmbientParticles:true},directives:{loading:()=>{}},mocks:{$router:{push:vi.fn()}}}})
describe('world first creation interface',()=>{
 it('shows the generated introduction before revealing questions',async()=>{
  mock.post.mockImplementation(url=>Promise.resolve(url==='/game/start'?{sessionId:'new',status:'WORLD_PENDING',worldPending:true,questions:[]}:ready))
  const page=mountPage();await flushPromises()
  expect(mock.post.mock.calls.map(x=>x[0])).toEqual(['/game/start'])
  expect(page.findAll('input[name="world-theme"]')).toHaveLength(3)
  await page.find('.creation-world button').trigger('click');await flushPromises()
  expect(mock.post.mock.calls.map(x=>x[0])).toEqual(['/game/start','/game/world'])
  expect(page.text()).toContain(world.introduction);expect(page.find('el-form-stub').exists()).toBe(false)
  await page.find('.world-start-questions').trigger('click')
  expect(page.find('el-form-stub').exists()).toBe(true);page.unmount()
 })
 it('restores the saved world without generating another one on refresh',async()=>{
  store.setActiveSession({sessionId:'new',status:'CREATING'});mock.get.mockResolvedValue(ready)
  const page=mountPage();await flushPromises()
  expect(page.text()).toContain(world.title);expect(mock.post).not.toHaveBeenCalled();page.unmount()
 })
 it('keeps the session when generation fails and retries that same world endpoint',async()=>{
  mock.post.mockImplementation(url=>url==='/game/start'?Promise.resolve({sessionId:'new',status:'WORLD_PENDING',worldPending:true}):Promise.reject(new Error('offline')))
  const page=mountPage();await flushPromises();await page.find('.creation-world button').trigger('click');await flushPromises();expect(store.sessionId).toBe('new');expect(page.text()).toContain('尚未建立新存档')
  expect(sessionStorage.getItem('bs-selected-session:u')).toBeNull()
  mock.post.mockResolvedValue(ready);await page.find('.creation-world button').trigger('click');await flushPromises()
  expect(mock.post.mock.calls.at(-1)).toEqual(['/game/world',{sessionId:'new',playerId:'u',worldTheme:'WESTERN'}])
  expect(page.text()).toContain(world.introduction);page.unmount()
 })
 it('offers a nearby return to the world without losing the answers or generating twice',async()=>{
  mock.post.mockResolvedValue(ready);const page=mountPage();await flushPromises()
  await page.find('.world-start-questions').trigger('click');page.vm.answers=['林舟','亲友','先观察'];await flushPromises()
  await page.findAll('.creation-return-bar button')[1].trigger('click')
  expect(page.text()).toContain(world.introduction);expect(page.vm.answers).toEqual(['林舟','亲友','先观察'])
  expect(mock.post).toHaveBeenCalledTimes(1);page.unmount()
 })
 it('discards an unauthored draft on return, without remembering it as a save',async()=>{
  mock.post.mockImplementation(url=>url==='/game/start'?Promise.resolve({sessionId:'new',status:'WORLD_PENDING',worldPending:true}):Promise.reject(Error('offline')))
  const page=mountPage();await flushPromises();await page.find('.creation-return-bar button').trigger('click');await flushPromises()
  expect(mock.post).toHaveBeenCalledWith('/game/creation/abandon',{sessionId:'new',playerId:'u'},{timeout:2000})
  expect(store.sessionId).toBeNull();expect(store.playerId).toBe('u');expect(sessionStorage.getItem('bs-selected-session:u')).toBeNull();page.unmount()
 })
 it('discards a delayed world response after changing saves',async()=>{
  let resolveWorld;mock.post.mockImplementation(url=>url==='/game/start'?Promise.resolve({sessionId:'new',status:'WORLD_PENDING',worldPending:true}):new Promise(resolve=>{resolveWorld=resolve}))
  const page=mountPage();await flushPromises();await page.find('.creation-world button').trigger('click');await flushPromises();store.setActiveSession({sessionId:'B',status:'CREATING'});resolveWorld(ready);await flushPromises()
  expect(page.text()).not.toContain(world.introduction);expect(store.sessionId).toBe('B');page.unmount()
 })
 it('does not generate a world for another save when an old start retry finishes',async()=>{
  mock.post.mockRejectedValueOnce(new Error('start temporarily unavailable'))
  const page=mountPage();await flushPromises()
  let finishStart
  mock.post.mockImplementation(path=>path==='/game/start'?new Promise(resolve=>{finishStart=resolve}):Promise.resolve(ready))
  try{
   const retry=page.vm.beginWorld();await flushPromises()
   store.setActiveSession({sessionId:'other-save',status:'CREATING'})
   finishStart({sessionId:'obsolete-draft',status:'WORLD_PENDING',worldPending:true});await retry;await flushPromises()
   expect(mock.post.mock.calls.some(([path])=>path==='/game/world')).toBe(false)
   expect(store.sessionId).toBe('other-save');expect(store.archiveReady).not.toBe(true)
  }finally{page.unmount()}
 })
 it('can generate a world after successfully retrying its own failed start',async()=>{
  mock.post.mockRejectedValueOnce(new Error('start temporarily unavailable'))
  const page=mountPage();await flushPromises()
  mock.post.mockImplementation(path=>Promise.resolve(path==='/game/start'?{sessionId:'retried-draft',status:'WORLD_PENDING',worldPending:true}:ready))
  try{
   await page.vm.beginWorld();await flushPromises()
   expect(mock.post.mock.calls.filter(([path])=>path==='/game/world')).toEqual([['/game/world',{sessionId:'retried-draft',playerId:'u',worldTheme:'WESTERN'}]])
   expect(store.sessionId).toBe('retried-draft');expect(page.text()).toContain(world.introduction)
  }finally{page.unmount()}
 })
 it.each(['WESTERN','EASTERN','MODERN'])('sends the selected %s theme only after confirmation',async theme=>{
  mock.post.mockImplementation(url=>Promise.resolve(url==='/game/start'?{sessionId:'new',status:'WORLD_PENDING',worldPending:true}:ready))
  const page=mountPage();await flushPromises();await page.find(`input[value="${theme}"]`).setValue()
  expect(mock.post).toHaveBeenCalledTimes(1)
  await page.find('.creation-world button').trigger('click');await flushPromises()
  expect(mock.post).toHaveBeenLastCalledWith('/game/world',{sessionId:'new',playerId:'u',worldTheme:theme});page.unmount()
 })
})

