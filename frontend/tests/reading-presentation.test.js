import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {mount,shallowMount,flushPromises} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useSessionStore} from '../src/store/session'
import {characterPanelPresentation,navigationSelection} from '../src/utils/panelPresentation'
import Play from '../src/views/PlayView.vue'
import Profile from '../src/components/CharacterPanel.vue'
import {storyArrival} from '../src/utils/storyArrival'
const mocks=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),push:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mocks.get,post:mocks.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:mocks.push}),useRoute:()=>({path:'/play'})}))
let views,pinia
beforeEach(()=>{
 views=[];pinia=createPinia();setActivePinia(pinia);useSessionStore().setActiveSession({playerId:'fixture-user',sessionId:'A',status:'PLAYING',branchEpoch:0,revision:1})
 Object.assign(characterPanelPresentation,{open:false,tab:'self'});mocks.post.mockReset();mocks.get.mockImplementation(async path=>{
  if(path==='/game/scene')return {sceneId:'station',revision:1,branchEpoch:0,sessionStatus:'PLAYING',presentGods:[],playerGodId:'self',worldSetting:{title:'测试卷'}}
  if(path==='/game/transcript')return {sessionId:'A',revision:1,branchEpoch:0,turns:[]}
  if(path==='/game/sessions')return {sessions:[{sessionId:'A',status:'PLAYING'}]}
  if(path==='/game/character')return {name:'测试人物',health:100,status:'PLAYING',baseStats:{zhi:1,hui:1,dou:1,wen:1,ben:1,gui:1},actualStats:{zhi:1,hui:1,dou:1,wen:1,ben:1,gui:1},importantItems:[],friends:[],enemies:[],acquaintances:[]}
  return {}
 })
})
afterEach(()=>{views.forEach(view=>view.unmount());vi.restoreAllMocks()})
const globalOptions=()=>({plugins:[pinia],stubs:{AmbientParticles:true},renderStubDefaultSlot:true,mocks:{$router:{push:mocks.push}}})
const rail=(play,label)=>play.findAll('[aria-label="旅程导航"]>button').find(button=>button.text()===label)

it('keeps the authored opening readable when the first pending or completed chapter exists',async()=>{
 const ordinary=mocks.get.getMockImplementation()
 mocks.get.mockImplementation(path=>path==='/game/transcript'?Promise.resolve({sessionId:'A',revision:1,branchEpoch:0,opening:{text:'广播念出莉安的名字，沈砚尚未登车。'},turns:[{id:1,round:1,operationStatus:'RUNNING',playerInput:'询问莉安',actors:[]}]}):ordinary(path))
 const play=shallowMount(Play,{global:globalOptions()});views.push(play);await flushPromises()
 expect(play.find('.opening-story').text()).toContain('广播念出莉安的名字')
 expect(play.findAll('.story-chapter')).toHaveLength(0)
})
it('does not republish an old empty partial chapter after refresh and keeps its real recovery state visible',async()=>{
 const ordinary=mocks.get.getMockImplementation()
 const current={sceneId:'station',revision:1,branchEpoch:0,sessionStatus:'PLAYING',pendingOperationId:'old-partial',presentGods:[],playerGodId:'self',worldSetting:{title:'测试卷'}}
 const issues=[{kind:'STORY_RESULT_UNAVAILABLE',stage:'RESULT_UNRESOLVED'}]
 mocks.get.mockImplementation(path=>{
  if(path==='/game/scene')return Promise.resolve(current)
  if(path==='/game/transcript')return Promise.resolve({sessionId:'A',revision:1,branchEpoch:0,opening:{text:'主角依然在格里姆的铺子里。'},turns:[{id:88,round:1,operationStatus:'PARTIAL',playerInput:'继续学习本事，陪伴老格里姆',actors:[],technicalIssues:issues}]})
  if(path==='/game/operations/old-partial')return Promise.resolve({operationId:'old-partial',logId:88,round:1,status:'PARTIAL',pendingKind:'RESULT_UNRESOLVED',playerInput:'继续学习本事，陪伴老格里姆',branchEpoch:0,revision:1,scene:current,lines:[],ruleSnapshot:{technicalIssues:issues}})
  return ordinary(path)
 })
 const play=shallowMount(Play,{global:globalOptions()});views.push(play);await flushPromises()
 expect(play.find('.opening-story').text()).toContain('格里姆的铺子')
 expect(play.findAll('.story-chapter')).toHaveLength(0)
 const recovery=play.get('.operation-recovery').text()
 expect(recovery).toContain('此段故事尚未生成完成');expect(recovery).toContain('查询进度');expect(recovery).toContain('续接未完成部分')
 expect(recovery).not.toContain('尚未取得的人物反应');expect(play.vm.pendingOperation.operationId).toBe('old-partial')
 expect(mocks.post).not.toHaveBeenCalled()
})
it('renders legacy narrator transport labels as prose without changing clocks or dialogue',async()=>{
 const ordinary=mocks.get.getMockImplementation()
 mocks.get.mockImplementation(path=>path==='/game/transcript'?Promise.resolve({sessionId:'A',revision:1,branchEpoch:0,turns:[{id:1,round:1,actors:[{godId:'narrator',godName:'旁白',displayedDialogue:'narrator: 钟停在6:17。\n\n“莉安：我还活着。”'}]}]}):ordinary(path))
 const play=shallowMount(Play,{global:globalOptions()});views.push(play);await flushPromises()
 expect(play.find('.story-chapter .story-prose').text()).toBe('钟停在6:17。“莉安：我还活着。”')
})

it('reflects profile tab changes in the play rail and restores story after closing',async()=>{
 const play=shallowMount(Play,{global:globalOptions()}),profile=shallowMount(Profile,{global:globalOptions()});views.push(play,profile);await flushPromises()
 await rail(play,'人物').trigger('click');await flushPromises();expect(rail(play,'人物').attributes('aria-pressed')).toBe('true');expect(rail(play,'故事').attributes('aria-current')).toBeUndefined()
 await profile.findAll('.profile-tabs button')[1].trigger('click');expect(rail(play,'行囊').attributes('aria-pressed')).toBe('true');expect(rail(play,'人物').attributes('aria-pressed')).toBe('false')
 profile.findComponent({name:'ElDrawer'}).vm.$emit('update:modelValue',false);await flushPromises();expect(rail(play,'故事').attributes('aria-current')).toBe('page');expect(mocks.post).not.toHaveBeenCalled()
})
it('closes the previous main panel instead of stacking a new profile over the world book',async()=>{
 const play=shallowMount(Play,{global:globalOptions()}),profile=shallowMount(Profile,{global:globalOptions()});views.push(play,profile);await flushPromises()
 await rail(play,'世诰').trigger('click');expect(rail(play,'世诰').attributes('aria-expanded')).toBe('true')
 window.dispatchEvent(new CustomEvent('open-character-panel',{detail:{tab:'self'}}));await flushPromises()
 expect(rail(play,'世诰').attributes('aria-expanded')).toBe('false');expect(rail(play,'人物').attributes('aria-expanded')).toBe('true')
})
it('keeps relationships under people and does not mark story selected under an unrelated modal',()=>{
 expect(navigationSelection({characterOpen:true,characterTab:'relations'})).toBe('self')
 expect(navigationSelection({info:true,infoTab:'六维'})).toBe('stats')
 expect(navigationSelection({other:true})).toBe('other');expect(navigationSelection({})).toBe('story')
})
it('reveals new paragraphs once, while restored chapters and unchanged polling remain still',async()=>{
 const descriptor=Object.getOwnPropertyDescriptor(HTMLElement.prototype,'animate'),animate=vi.fn(()=>({cancel:vi.fn()}))
 Object.defineProperty(HTMLElement.prototype,'animate',{configurable:true,value:animate})
 try{
  const view=mount({props:['arrival'],directives:{storyArrival},template:'<article v-story-arrival="arrival"><div class="chapter-mark">新页</div><div class="story-prose"><p>第一段</p><p>第二段</p></div></article>'},{props:{arrival:false}});views.push(view)
  expect(animate).not.toHaveBeenCalled();await view.setProps({arrival:true});expect(animate).toHaveBeenCalledTimes(3)
  await view.setProps({arrival:true});expect(animate).toHaveBeenCalledTimes(3);expect(view.text()).toContain('第一段')
 }finally{if(descriptor)Object.defineProperty(HTMLElement.prototype,'animate',descriptor);else delete HTMLElement.prototype.animate}
})
