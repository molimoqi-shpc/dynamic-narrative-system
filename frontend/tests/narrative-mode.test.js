import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount,shallowMount,flushPromises} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ReadingSettings from '../src/components/ReadingSettings.vue'
import {READING_KEY,readingDefaults,loadReading,saveReading,normalizeReading,readingVariables} from '../src/utils/readingPreferences'
import {useSessionStore} from '../src/store/session'
const api=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),push:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:api.get,post:api.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:api.push})}))
import Play from '../src/views/PlayView.vue'

let pinia,views
const scene=revision=>({sceneId:'workshop',branchEpoch:0,revision,playerGodId:'self',presentGods:[],objectives:[],exits:[],viewMode:'DREAMER',sessionStatus:'PLAYING'})
beforeEach(()=>{
 localStorage.clear();sessionStorage.clear();views=[];pinia=createPinia();setActivePinia(pinia)
 useSessionStore().setActiveSession({playerId:'user',sessionId:'A',status:'PLAYING',branchEpoch:0,revision:0})
 api.post.mockReset();api.get.mockReset().mockImplementation(async path=>{
  if(path==='/game/scene')return scene(0)
  if(path==='/game/transcript')return {sessionId:'A',branchEpoch:0,revision:0,turns:[]}
  return {exits:[],locations:[],decisions:[],memories:[]}
 })
})
afterEach(()=>views.forEach(view=>view.unmount()))
function play(){const view=shallowMount(Play,{global:{plugins:[pinia],stubs:{ReadingSettings:false,AmbientParticles:true,teleport:true},renderStubDefaultSlot:true,mocks:{$router:{push:api.push}}}});views.push(view);return view}
function result(body,status='COMPLETED'){
 return {operationId:body.operationId,playerInput:body.playerText,logId:1,round:1,status,pendingKind:status==='PARTIAL'?'RESULT_UNRESOLVED':undefined,branchEpoch:0,revision:1,scene:{...scene(1),pendingOperationId:status==='PARTIAL'?body.operationId:undefined},lines:status==='COMPLETED'?[{godId:'narrator',speech:'师傅把石料递过来，主角接过继续练习。'}]:[],ruleSnapshot:{}}
}

describe('narrative length preference',()=>{
 it('migrates old reading settings to NORMAL without losing typography and persists an explicit LONG choice',()=>{
  const old={size:20,spacing:1.2,leading:1.8,density:.6,font:'kai'}
  localStorage.setItem(READING_KEY,JSON.stringify(old))
  expect(loadReading(localStorage)).toEqual({...old,narrativeMode:'NORMAL'})
  expect(normalizeReading({narrativeMode:'unrecognized'}).narrativeMode).toBe('NORMAL')
  saveReading(localStorage,{...old,narrativeMode:'LONG'})
  expect(loadReading(localStorage)).toEqual({...old,narrativeMode:'LONG'})
  expect(readingVariables({...old,narrativeMode:'LONG'})).toEqual(readingVariables({...old,narrativeMode:'NORMAL'}))
 })
 it('places both modes with typography settings and explains that existing or pending prose is unchanged',async()=>{
  const view=mount(ReadingSettings,{props:{open:true,modelValue:{...readingDefaults}},global:{stubs:{teleport:true}}});views.push(view)
  const buttons=view.findAll('.reading-narrative button')
  expect(buttons.map(button=>button.find('strong').text())).toEqual(['正常篇幅','长篇沉浸'])
  expect(buttons[0].attributes('aria-pressed')).toBe('true')
  expect(view.text()).toContain('至少500字');expect(view.text()).toContain('已有正文不重写');expect(view.text()).toContain('进行中的故事沿用提交时的篇幅模式')
  await buttons[1].trigger('click')
  const changed=view.emitted('update:modelValue').at(-1)[0]
  expect(changed).toEqual({...readingDefaults,narrativeMode:'LONG'})
  await view.setProps({modelValue:changed})
  expect(view.findAll('.reading-narrative button')[1].attributes('aria-pressed')).toBe('true')
  expect(api.post).not.toHaveBeenCalled()
 })
 it('sends NORMAL by default when a new story action is submitted',async()=>{
  api.post.mockImplementation(async(path,body)=>result(body))
  const view=play();await flushPromises();view.vm.playerAction='继续学习本事，陪伴师傅'
  await view.vm.onSubmit();await flushPromises()
  expect(api.post).toHaveBeenCalledTimes(1)
  expect(api.post.mock.calls[0][0]).toBe('/game/operations')
  expect(api.post.mock.calls[0][1]).toMatchObject({narrativeMode:'NORMAL',playerText:'继续学习本事，陪伴师傅',storyMode:true})
 })
 it('sends the selected LONG mode once and keeps later preference changes out of recovery requests',async()=>{
  let request
  api.post.mockImplementation(async(path,body)=>{
   if(path==='/game/operations'){request=body;return result(body,'PARTIAL')}
   return result(request)
  })
  const view=play();await flushPromises()
  await view.get('button[aria-controls="reading-preferences"]').trigger('click')
  await view.get('.reading-narrative button:nth-child(2)').trigger('click');await flushPromises()
  expect(loadReading(localStorage).narrativeMode).toBe('LONG')
  expect(api.post).not.toHaveBeenCalled()
  view.vm.playerAction='继续学习本事，陪伴师傅';await view.vm.onSubmit();await flushPromises()
  expect(request.narrativeMode).toBe('LONG')
  await view.get('.reading-narrative button:nth-child(1)').trigger('click');await flushPromises()
  expect(loadReading(localStorage).narrativeMode).toBe('NORMAL');expect(api.post).toHaveBeenCalledTimes(1)
  await view.vm.resumeOperation();await flushPromises()
  expect(api.post).toHaveBeenCalledTimes(2)
  const [path,body,options]=api.post.mock.calls[1]
  expect(path).toBe(`/game/operations/${request.operationId}/recover`)
  expect(body).toBeNull();expect(options.params).toEqual({sessionId:'A',branchEpoch:0})
  expect(request.narrativeMode).toBe('LONG')
 })
})
