import {describe,it,expect,beforeEach,afterEach,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {shallowMount,flushPromises} from '@vue/test-utils'
import {useSessionStore} from '../src/store/session.js'
import {requestIdentity} from '../src/utils/requestIdentity.js'
import {operationNotice,reconcilePendingOperation} from '../src/utils/gameOperation.js'
const mocks=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),push:vi.fn(),replace:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mocks.get,post:mocks.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:mocks.push,replace:mocks.replace})}))
import Creation from '../src/views/CharacterCreationView.vue'
import Play from '../src/views/PlayView.vue'
import SceneRail from '../src/components/GameSceneRail.vue'
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
let pinia,store
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);store=useSessionStore();store.setActiveSession({playerId:'user',sessionId:'A',status:'PLAYING',branchEpoch:0});sessionStorage.clear();mocks.get.mockReset();mocks.post.mockReset()})
afterEach(()=>vi.useRealTimers())
const globalOptions=()=>({plugins:[pinia],stubs:{AmbientParticles:true,GameSceneRail:true,StoryDecisionNote:false},mocks:{$router:{push:mocks.push}}})

describe('objective panel server contract',()=>{
 it('shows currentStep and the server progress instead of an absent description field',()=>{
  const panel=shallowMount(SceneRail,{props:{atmosphere:{position:'50% 50%'},objectives:[{objectiveId:'observe',title:'探明退路',status:'ACTIVE',progress:50,currentStep:'观察坡向并选择安全山径'}]}})
  expect(panel.text()).toContain('观察坡向并选择安全山径');expect(panel.text()).toContain('50%');expect(panel.find('progress').attributes('value')).toBe('50');panel.unmount()
 })
})
describe('joint request and storage ownership',()=>{
 it('keeps the real store identity even when writes and removals throw',()=>{
  const spy=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new DOMException('denied','SecurityError')})
  expect(()=>store.setPendingOperation({operationId:'accepted'})).not.toThrow();expect(store.pendingOperation.operationId).toBe('accepted');spy.mockRestore()
  const remove=vi.spyOn(Storage.prototype,'removeItem').mockImplementation(()=>{throw new DOMException('full','QuotaExceededError')})
  expect(()=>store.clearSession()).not.toThrow();expect(store.sessionId).toBeNull();remove.mockRestore()
 })
 it('invalidates an old request after A -> B -> A',()=>{
  const valid=requestIdentity(store);store.setActiveSession({sessionId:'B'});store.setActiveSession({sessionId:'A'});expect(valid()).toBe(false)
 })
 it('distinguishes branch invalidity from ownership needed to release loading',()=>{
  const valid=requestIdentity(store);store.branchEpoch=1;expect(valid()).toBe(false);expect(valid.owned()).toBe(true)
 })
 it('keeps model payment, rate limit and timeout outside narrative',()=>{
  expect(operationNotice('PARTIAL','MODEL_PAYMENT_REQUIRED')).toContain('余额不足');expect(operationNotice('PARTIAL','MODEL_RATE_LIMITED')).toContain('限流');expect(operationNotice('PARTIAL','MODEL_TIMEOUT')).toContain('超时')
 })
 it('explains that the request limit pauses generation and recovery permits more usage',()=>{
  expect(operationNotice('PARTIAL','MODEL_CALL_LIMIT')).toContain('额度已用完');expect(operationNotice('PARTIAL','MODEL_CALL_LIMIT')).toContain('查询进度不会追加');expect(operationNotice('PARTIAL','MODEL_CALL_LIMIT')).toContain('追加模型用量')
 })
})
describe('mounted creation page late responses',()=>{
 it('cannot overwrite B when the old start for A returns',async()=>{
  store.setActiveSession({sessionId:null,status:null});const pending=deferred();mocks.post.mockReturnValue(pending.promise)
  const page=shallowMount(Creation,{global:globalOptions()});await flushPromises();store.setActiveSession({sessionId:'B',status:'PLAYING'})
  pending.resolve({sessionId:'created-A',questions:['one']});await flushPromises();expect(store.sessionId).toBe('B');expect(store.status).toBe('PLAYING');page.unmount()
 })
 it('does not apply start after the component was unmounted',async()=>{
  store.setActiveSession({sessionId:null,status:null});const pending=deferred();mocks.post.mockReturnValue(pending.promise)
  const page=shallowMount(Creation,{global:globalOptions()});page.unmount();pending.resolve({sessionId:'late',questions:[]});await flushPromises();expect(store.sessionId).toBeNull()
 })
 it('does accept a valid first start from no selected session',async()=>{
  store.setActiveSession({sessionId:null,status:null});mocks.post.mockResolvedValue({sessionId:'new',questions:['one','two','three']})
  const page=shallowMount(Creation,{global:globalOptions()});await flushPromises();expect(store.sessionId).toBe('new');expect(store.status).toBe('CREATING');expect(page.vm.loadingStart).toBe(false);page.unmount()
 })
})
function scene(epoch=0,revision=0){return {sceneId:'s',branchEpoch:epoch,revision,playerGodId:'p',presentGods:[],objectives:[],exits:[],viewMode:'DREAMER'}}
describe('creation recovery and terminal projection',()=>{
 it('restores original answers with the accepted talents',async()=>{
  store.setActiveSession({status:'CREATING'});mocks.get.mockResolvedValue({sessionId:'A',status:'TALENT_GENERATED',questions:['一','二','三'],answers:['原一','原二','原三'],talents:[{talentId:1,talentName:'细察'}]})
  const page=shallowMount(Creation,{global:globalOptions()});await flushPromises();expect(page.vm.answers).toEqual(['原一','原二','原三']);expect(page.vm.step).toBe(1)
  await page.vm.submitAnswers();expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('recovers a committed birth after the response was lost',async()=>{
  store.setActiveSession({status:'CREATING'});mocks.get.mockResolvedValue({sessionId:'A',status:'COMPLETED',sceneId:'born-scene',playerGodId:'born-player',birthStory:'原故事',initialLocation:{locationName:'河边'},objectives:[{title:'观察'}]})
  const page=shallowMount(Creation,{global:globalOptions()});await flushPromises();expect(page.vm.step).toBe(2);expect(page.vm.birthStory).toBe('原故事');expect(store.status).toBe('PLAYING');expect(store.sceneId).toBe('born-scene');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('uses the selected creation on a browser refresh instead of starting another session',async()=>{
  store.setActiveSession({sessionId:null,status:null});sessionStorage.setItem('bs-selected-session:user','saved-creation');mocks.get.mockResolvedValue({sessionId:'saved-creation',status:'QUESTIONS',questions:['一','二','三'],answers:['','','']})
  const page=shallowMount(Creation,{global:globalOptions()});await flushPromises();expect(store.sessionId).toBe('saved-creation');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('applies a server victory immediately and stops accepting new actions',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.applyScene({...scene(0,2),sessionStatus:'VICTORY',playerHealth:75,objectives:[{status:'COMPLETED'}]});expect(store.status).toBe('VICTORY');expect(page.vm.deathDialogVisible).toBe(true);expect(page.vm.finalEvaluation).toBe('');page.unmount()
 })
})
function ordinaryReads(path){if(path==='/game/scene')return Promise.resolve(scene());if(path==='/game/transcript')return Promise.resolve({turns:[],branchEpoch:0,revision:0});return Promise.resolve({exits:[],locations:[],decisions:[],memories:[]})}

describe('story drafts preserve evidence and leave outcomes to the author',()=>{
 it('includes selected notebook contents and provenance without sending automatically',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();page.vm.applyScene({...scene(),presentGods:[{godId:'npc',godName:'渡者'}]});page.vm.knowledgeRecipient='npc'
  const entry={title:'旧案线索',content:'失踪者最后出现在北堤，消息仍未证实。',sourceLabel:'亲耳听见',verificationNote:'原账核对仅证明姓名一致，去向仍未证实'}
  page.vm.draftKnowledgeShare(entry);expect(page.vm.playerAction).toContain(entry.content);expect(page.vm.playerAction).toContain('亲耳听见');expect(page.vm.playerAction).toContain(entry.verificationNote);expect(page.vm.playerAction).toContain('不代表已经证实');expect(mocks.post).not.toHaveBeenCalled()
  page.vm.draftKnowledgeQuestion(entry);expect(page.vm.playerAction).toContain(entry.content);expect(page.vm.playerAction).toContain('亲见依据');page.unmount()
 })
 it('bounds a long note as an honest excerpt within the action input limit',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();page.vm.applyScene({...scene(),presentGods:[{godId:'npc',godName:'渡者'}]});page.vm.knowledgeRecipient='npc'
  page.vm.draftKnowledgeShare({title:'长记录',content:'潮'.repeat(4000),sourceLabel:'亲见'});expect(page.vm.playerAction.length).toBeLessThan(4000);expect(page.vm.playerAction).toContain('以下为节选');page.unmount()
 })
 it('drafts attempts to contact a known absent counterpart instead of blocking consent',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.draftTreaty({targetId:'distant',targetName:'知秋',text:'我解除与你的盟约。'});expect(page.vm.composeMode).toBe('action');expect(page.vm.playerAction).toContain('尝试联系知秋');expect(page.vm.playerAction).toContain('我解除与你的盟约');expect(page.vm.playerAction).toContain('不视为已经送达');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('keeps a visible counterpart response in the direct speech draft',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();page.vm.applyScene({...scene(),presentGods:[{godId:'npc',godName:'渡者'}]})
  page.vm.draftTreaty({targetId:'npc',targetName:'渡者',text:'我同意与你结盟。'});expect(page.vm.composeMode).toBe('talk');expect(page.vm.knowledgeRecipient).toBe('npc');expect(page.vm.playerAction).toBe('我同意与你结盟。');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('offers a general inquiry direction when nobody is selected',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();page.vm.visitedLocations=[{sceneId:'old',locationName:'北堤'}];page.vm.selectedCoordKey='old';await page.vm.onInquire()
  expect(page.vm.composeMode).toBe('action');expect(page.vm.playerAction).toContain('尝试打听北堤');expect(page.vm.actionError).toBe('');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
})

describe('life ending presentation',()=>{
 it('ignores ending details from before an in-place rewind',async()=>{
  const ending=deferred();mocks.get.mockImplementation(path=>path==='/game/session-status'?ending.promise:path==='/game/scene'?Promise.resolve({...scene(),sessionStatus:'DEAD',playerHealth:0}):ordinaryReads(path))
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  expect(mocks.get.mock.calls.some(([path])=>path==='/game/session-status')).toBe(true)
  store.setActiveSession({branchEpoch:1,status:'PLAYING'});page.vm.deathDialogVisible=false;page.vm.deathSummary=''
  ending.resolve({endingSummary:'旧分支结局',finalEvaluation:'旧评价'});await flushPromises()
  expect(page.vm.deathDialogVisible).toBe(false);expect(page.vm.deathSummary).toBe('');page.unmount()
 })
 it('keeps the saved scene readable when fetching ending details fails',async()=>{
  mocks.get.mockImplementation(path=>path==='/game/session-status'?Promise.reject(new Error('offline')):path==='/game/scene'?Promise.resolve({...scene(),sessionStatus:'RETIRED'}):ordinaryReads(path))
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  expect(page.vm.actionError).toContain('结局资料暂未读取成功');expect(store.status).toBe('RETIRED');page.unmount()
 })
 it('keeps completed milestones playable without an ending dialog',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.applyScene({...scene(0,2),sessionStatus:'PLAYING',objectives:[{status:'COMPLETED',progress:100}]})
  expect(page.vm.deathDialogVisible).toBe(false);expect(store.status).toBe('PLAYING');page.unmount()
 })
 it('shows the authored retirement epilogue and evaluation, with read access to the save',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.applyScene({...scene(0,2),sessionStatus:'RETIRED',endingSummary:'归隐后回看摆渡的一生。',finalEvaluation:'有取舍，也有代价。'})
  expect(store.canPlay).toBe(true);expect(page.vm.deathDialogVisible).toBe(true);expect(page.vm.deathSummary).toContain('摆渡');expect(page.vm.finalEvaluation).toContain('代价');page.unmount()
 })
 it('shows the saved death epilogue without posting another generation request',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.applyScene({...scene(0,2),sessionStatus:'DEAD',playerHealth:0,endingSummary:'最后想起父亲的船。',finalEvaluation:'此生终评。'});await flushPromises()
  expect(page.vm.deathSummary).toContain('父亲');expect(page.vm.finalEvaluation).toBe('此生终评。');expect(page.text()).toContain('已陨落');expect(page.text()).toContain('此生已尽');expect(page.text()).not.toContain('已身受重创');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
})

describe('explicit legacy objective replacement',()=>{
 it('labels carried activity as historical without discarding physical symptoms',async()=>{
  const panel=shallowMount(SceneRail,{props:{atmosphere:{position:'50% 50%'},scene:{playerPhysicalState:{position:'抵达苔痕坪',awake:true,bound:false,activity:'右脚仍然酸痛',activityHistorical:true}}}})
  expect(panel.text()).toContain('此前记录：右脚仍然酸痛');expect(panel.text()).toContain('抵达苔痕坪')
  await panel.setProps({scene:{playerPhysicalState:{activity:'在古碑旁观察',activityHistorical:false}}});expect(panel.text()).toContain('当前状态：在古碑旁观察');expect(panel.text()).not.toContain('此前记录：');panel.unmount()
 })
 const proposal=()=>({available:true,mayFinish:true,previewToken:'reviewed-token',branchEpoch:0,revision:0,originalTitles:['了解四方意图'],replacements:[{title:'看清眼前的处境',condition:'实际观察当前地点',satisfied:true},{title:'走出初见之地',condition:'亲访两处地点',satisfied:true}]})
 it('exposes repair only as a player action and disables it while an operation is pending',async()=>{
  const panel=shallowMount(SceneRail,{props:{atmosphere:{position:'50% 50%'},legacyRepair:proposal()}})
  const button=panel.findAll('button').find(b=>b.text()==='查看重订开局目标');await button.trigger('click');expect(panel.emitted('repair-objectives')).toHaveLength(1)
  await panel.setProps({busy:true});expect(button.attributes('disabled')).toBeDefined();panel.unmount()
 })
 it('loads a preview without posting or changing the session and warns of immediate victory',async()=>{
  mocks.get.mockImplementation(path=>path==='/game/objectives/legacy-repair'?Promise.resolve(proposal()):ordinaryReads(path))
  const page=shallowMount(Play,{global:{...globalOptions(),stubs:{...globalOptions().stubs,ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}});await flushPromises()
  await page.vm.showLegacyRepair();expect(page.text()).toContain('完成后仍可继续游玩');expect(page.text()).toContain('原目标与规则保留');expect(mocks.post).not.toHaveBeenCalled();expect(store.status).toBe('PLAYING');page.unmount()
 })
 it('posts the exact accepted preview then reloads the resulting terminal state',async()=>{
  let repaired=false
  mocks.get.mockImplementation(path=>path==='/game/objectives/legacy-repair'?Promise.resolve(repaired?{available:false}:proposal()):path==='/game/scene' && repaired?Promise.resolve({...scene(0,1),sessionStatus:'VICTORY'}):path==='/game/transcript' && repaired?Promise.resolve({sessionId:'A',turns:[],branchEpoch:0,revision:1}):ordinaryReads(path))
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises();await page.vm.showLegacyRepair()
  mocks.post.mockImplementation(()=>{repaired=true;return Promise.resolve({repaired:true})})
  await page.vm.applyLegacyRepair();await flushPromises()
  expect(mocks.post).toHaveBeenCalledWith('/game/objectives/legacy-repair',{sessionId:'A',branchEpoch:0,expectedRevision:0,previewToken:'reviewed-token'});expect(store.status).toBe('VICTORY');expect(page.vm.actionBlocked).toBe(false);page.unmount()
 })
 it('cannot install a late repair preview after switching saves',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const response=deferred();mocks.get.mockImplementation(path=>path==='/game/objectives/legacy-repair'?response.promise:ordinaryReads(path))
  const open=page.vm.showLegacyRepair();store.setActiveSession({sessionId:'B'});response.resolve(proposal());await open;await flushPromises()
  expect(page.vm.legacyRepairDialog).toBe(false);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
})

describe('player input interpretation confirmation',()=>{
 function review(id='r1',revision=1){return {operationId:'input',logId:1,round:1,playerInput:'我想用布条擦自己的手，不接触别人的物品。',status:'PARTIAL',pendingKind:'INPUT_REVIEW_REQUIRED',inputReview:{reviewId:id,privateSegments:['我想用布条擦自己的手，不接触别人的物品。']},branchEpoch:0,revision,scene:scene(0,revision),ruleSnapshot:{technicalIssues:[],elapsedSeconds:0},lines:[]}}
 it('shows the non-executed interpretation and sends the exact review identity',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'input',branchEpoch:0});page.vm.applyOperation(review(),'A');await flushPromises();
  expect(page.text()).toContain('被理解为心想');expect(page.text()).toContain('按行动意愿重新理解');expect(page.text()).not.toContain('续接未完成部分');
  mocks.post.mockResolvedValue(review('r2',2));await page.vm.reviewInputMeaning('REINTERPRET_ACTION');expect(mocks.post).toHaveBeenCalledWith('/game/operations/input/input-review',{reviewId:'r1',choice:'REINTERPRET_ACTION'},{params:{sessionId:'A',branchEpoch:0},timeout:20000});expect(store.pendingOperation.inputReview.reviewId).toBe('r2');
  page.vm.applyOperation(review('r1',1),'A');expect(store.pendingOperation.inputReview.reviewId).toBe('r2');page.unmount()
 })
 it('keeps a confirmed private thought visibly non-executed after completion',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'input',branchEpoch:0});page.vm.applyOperation(review(),'A');
  const done={...review('r1',2),status:'COMPLETED',pendingKind:'',inputReview:undefined,ruleSnapshot:{inputNotice:'已按你的确认保留为心想，尚未执行，也未告知其他人物。',eventTimeline:[]}};mocks.post.mockResolvedValue(done);await page.vm.reviewInputMeaning('KEEP_PRIVATE');await flushPromises();expect(store.pendingOperation).toBeNull();expect(page.text()).toContain('尚未执行，也未告知其他人物');page.unmount()
 })
 it('cancel and edit restores the full input without silently converting it to action',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'input',branchEpoch:0});const original=review();page.vm.applyOperation(original,'A');mocks.post.mockResolvedValue({...original,status:'CANCELLED',revision:2,scene:scene(0,2)});await page.vm.editReviewedInput();expect(page.vm.playerAction).toBe(original.playerInput);expect(mocks.post).toHaveBeenCalledTimes(1);expect(mocks.post.mock.calls[0][0]).toContain('/cancel');page.unmount()
 })
 it.each(['save','branch'])('does not restore an old input after the %s changes during cancel and edit',async change=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const original=review();store.setPendingOperation({operationId:'input',branchEpoch:0});page.vm.applyOperation(original,'A')
  const cancellation=deferred();mocks.post.mockReturnValue(cancellation.promise)
  try{
   const editing=page.vm.editReviewedInput()
   if(change==='save')store.setActiveSession({sessionId:'B'})
   else{store.setActiveSession({branchEpoch:1});store.setPendingOperation(null)}
   cancellation.resolve({...original,status:'CANCELLED',revision:2,scene:scene(0,2)});await editing;await flushPromises()
   expect(page.vm.playerAction).toBe('');expect(page.vm.composeMode).toBe('action')
   expect(store.pendingOperation).toBeNull();expect(mocks.post).toHaveBeenCalledTimes(1)
  }finally{page.unmount()}
 })
})
describe('mounted play page stale-branch barrier',()=>{
 it('blocks new input until the new branch and chapter have both been read',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'old',branchEpoch:0,playerInput:'原行动'});const pending=deferred()
  mocks.get.mockImplementation(path=>path==='/game/scene'?pending.promise:path==='/game/transcript'?Promise.resolve({turns:[],branchEpoch:1,revision:8}):ordinaryReads(path))
  page.vm.applyOperation({operationId:'old',status:'STALE_BRANCH'},'A');expect(store.pendingOperation).toBeNull();expect(page.vm.syncRequired).toBe(true);expect(page.vm.actionBlocked).toBe(true)
  pending.resolve(scene(1,8));await flushPromises();expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(page.vm.playerAction).toBe('原行动');page.unmount()
 })
 it('does not clear a newer operation when a stale response arrives',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'new',branchEpoch:1,status:'PARTIAL'})
  page.vm.applyOperation({operationId:'old',status:'STALE_BRANCH'},'A');expect(store.pendingOperation.operationId).toBe('new');page.unmount()
 })
 it('keeps the synchronization barrier and retry control after a read error',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'old',branchEpoch:0})
  mocks.get.mockRejectedValue(new Error('offline'));page.vm.applyOperation({operationId:'old',status:'STALE_BRANCH'},'A');await flushPromises();expect(page.vm.syncRequired).toBe(true);expect(page.vm.actionBlocked).toBe(true);expect(page.text()).toContain('重新读取当前进展');page.unmount()
 })
})

describe('consistent projection bundle',()=>{
 it('retries free snapshots when a passage commits during browser refresh',async()=>{
  let sceneReads=0
  mocks.get.mockImplementation(path=>{
   if(path==='/game/scene')return Promise.resolve({...scene(0,sceneReads++===0?1:2),sessionStatus:'PLAYING'})
   if(path==='/game/transcript')return Promise.resolve({sessionId:'A',branchEpoch:0,revision:2,turns:[{id:2,actors:[{godId:'narrator',displayedDialogue:'这段故事已完成。'}]}]})
   return ordinaryReads(path)
  })
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  expect(sceneReads).toBe(2);expect(page.vm.scene.revision).toBe(2);expect(page.vm.turns[0].id).toBe('saved-2')
  expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('bounds inconsistent refresh retries and preserves a retry control',async()=>{
  let reads=0
  mocks.get.mockImplementation(path=>{
   if(path==='/game/scene'){reads++;return Promise.resolve(scene(0,1))}
   if(path==='/game/transcript')return Promise.resolve({sessionId:'A',branchEpoch:0,revision:2,turns:[]})
   return ordinaryReads(path)
  })
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  expect(reads).toBe(3);expect(page.vm.actionBlocked).toBe(true);expect(page.vm.busy).toBe(false);expect(mocks.post).not.toHaveBeenCalled()
  mocks.get.mockImplementation(ordinaryReads);await page.vm.refreshActionResult();expect(page.vm.actionBlocked).toBe(false);page.unmount()
 })
 it('releases scene loading when the server restores a different perspective',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(),viewMode:'OMNISCIENT'}):ordinaryReads(path))
  await page.vm.loadScene();expect(page.vm.loadingScene).toBe(false);expect(page.vm.busy).toBe(false);page.unmount()
 })
 it('keeps the old scene and chapter together when server versions disagree, then permits retry',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const previous=page.vm.scene;const previousTurns=page.vm.turns
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve(scene(1,8)):path==='/game/transcript'?Promise.resolve({sessionId:'A',turns:[],branchEpoch:1,revision:9}):ordinaryReads(path))
  await page.vm.resyncCurrentBranch();expect(page.vm.syncRequired).toBe(true);expect(page.vm.scene).toBe(previous);expect(page.vm.turns).toBe(previousTurns)
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve(scene(1,9)):path==='/game/transcript'?Promise.resolve({sessionId:'A',turns:[],branchEpoch:1,revision:9}):ordinaryReads(path))
  await page.vm.resyncCurrentBranch();expect(page.vm.syncRequired).toBe(false);expect(page.vm.scene.revision).toBe(9);page.unmount()
 })
 it('invalidates an old scene read before it can install its pending operation',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const old=deferred(),fresh=deferred();let reads=0
  mocks.get.mockImplementation(path=>path==='/game/scene'?(reads++===0?old.promise:fresh.promise):ordinaryReads(path))
  const first=page.vm.loadScene(),second=page.vm.loadScene();fresh.resolve({...scene(1,8),pendingOperationId:'current'});await second
  old.resolve({...scene(0,0),pendingOperationId:'obsolete'});await first
  expect(page.vm.scene.branchEpoch).toBe(1);expect(store.pendingOperation.operationId).toBe('current');page.unmount()
 })
})

it('does not install stale pending metadata when the world advances during a scene read',async()=>{
 mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
 const response=deferred();mocks.get.mockImplementation(path=>path==='/game/scene'?response.promise:ordinaryReads(path))
 const read=page.vm.loadScene();page.vm.applyScene(scene(0,8));store.setPendingOperation({operationId:'new',branchEpoch:0,status:'PARTIAL'})
 response.resolve({...scene(0,3),pendingOperationId:'obsolete'});await read
 expect(page.vm.scene.revision).toBe(8);expect(store.pendingOperation.operationId).toBe('new');page.unmount()
})

describe('rewind and projection recovery',()=>{
 it('restores a playable scene and its chapter together after a terminal rewind',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.applyScene({...scene(0,5),sessionStatus:'DEAD'});page.vm.turns=[{id:'saved-future',actors:[]}]
  mocks.post.mockResolvedValue({sessionStatus:'PLAYING',restoredSceneId:'restored',playerGodId:'p'})
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(1,8),sceneId:'restored',sessionStatus:'PLAYING'}):path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:1,revision:8,turns:[{id:2,round:2,actors:[]}]}):ordinaryReads(path))
  await page.vm.loadToRound(2);await flushPromises()
  expect(page.vm.scene.sceneId).toBe('restored');expect(store.branchEpoch).toBe(1);expect(store.status).toBe('PLAYING')
  expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(page.vm.deathDialogVisible).toBe(false);expect(page.vm.turns.map(t=>t.id)).toEqual(['saved-2']);page.unmount()
 })
 it('keeps input blocked after a committed rewind cannot yet read its chapter, then retries without another rewind',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.turns=[{id:'saved-before-read',actors:[]}];const previousScene=page.vm.scene,previousTurns=page.vm.turns
  mocks.post.mockResolvedValue({sessionStatus:'PLAYING',restoredSceneId:'s',playerGodId:'p'})
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(1,8),sessionStatus:'PLAYING'}):path==='/game/transcript'?Promise.reject(new Error('read offline')):ordinaryReads(path))
  await page.vm.loadToRound(0);expect(page.vm.syncRequired).toBe(true);expect(page.vm.actionBlocked).toBe(true);expect(page.vm.scene).toBe(previousScene);expect(page.vm.turns).toBe(previousTurns)
  await page.vm.loadToRound(0);expect(mocks.post).toHaveBeenCalledTimes(1)
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(1,8),sessionStatus:'PLAYING'}):path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:1,revision:8,turns:[]}):ordinaryReads(path))
  await page.vm.resyncCurrentBranch();expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(page.vm.actionError).toBe('');expect(mocks.post).toHaveBeenCalledTimes(1);page.unmount()
 })
 it('treats a lost rewind response as requiring a read, without allowing a duplicate rewind',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  mocks.post.mockRejectedValue(new Error('response lost'));await page.vm.loadToRound(0)
  expect(page.vm.syncRequired).toBe(true);expect(page.vm.actionBlocked).toBe(true);expect(page.vm.actionError).toContain('无需重复回溯')
  await page.vm.loadToRound(0);expect(mocks.post).toHaveBeenCalledTimes(1)
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(1,8),sessionStatus:'PLAYING'}):path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:1,revision:8,turns:[]}):ordinaryReads(path))
  await page.vm.resyncCurrentBranch();expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(mocks.post).toHaveBeenCalledTimes(1);page.unmount()
 })
 it('does not mix older chapters from a different server branch into current history',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.turns=[{id:'saved-current',actors:[]}]
  mocks.get.mockImplementation(path=>path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:1,revision:2,turns:[{id:1,actors:[]}]}):ordinaryReads(path))
  await page.vm.loadTranscript(true);expect(page.vm.turns.map(t=>t.id)).toEqual(['saved-current']);expect(page.vm.syncRequired).toBe(true);page.unmount()
 })
 it('does not let a late rewind response replace a newly selected session',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const restore=deferred();mocks.post.mockReturnValue(restore.promise);const pending=page.vm.loadToRound(0)
  store.setActiveSession({sessionId:'B',sceneId:'B-scene',status:'PLAYING',branchEpoch:4});store.setPendingOperation({operationId:'B-op',branchEpoch:4})
  mocks.get.mockClear();restore.resolve({sessionStatus:'DEAD',restoredSceneId:'old-A',playerGodId:'old-role'});await pending
  expect(store.sessionId).toBe('B');expect(store.sceneId).toBe('B-scene');expect(store.status).toBe('PLAYING');expect(store.pendingOperation.operationId).toBe('B-op');expect(mocks.get).not.toHaveBeenCalled();page.unmount()
 })
})

describe('scene pending identity and auxiliary reads',()=>{
 it('clears a stale local pending marker when the authoritative scene has no pending operation',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'finished-elsewhere',branchEpoch:0,status:'PARTIAL'});await page.vm.loadScene();expect(store.pendingOperation).toBeNull();expect(page.vm.actionBlocked).toBe(false);page.unmount()
 })
 it('keeps review details only for the same pending operation and resets foreign metadata',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const pending={operationId:'review',branchEpoch:0,status:'PARTIAL',pendingKind:'INPUT_REVIEW_REQUIRED',inputReview:{reviewId:'r1'},playerInput:'原输入'};store.setPendingOperation(pending)
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(0,1),pendingOperationId:'review'}):ordinaryReads(path))
  await page.vm.loadScene();expect(store.pendingOperation.inputReview).toEqual({reviewId:'r1'});expect(store.pendingOperation.pendingKind).toBe('INPUT_REVIEW_REQUIRED')
  mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(0,2),pendingOperationId:'different'}):ordinaryReads(path))
  await page.vm.loadScene();expect(store.pendingOperation.operationId).toBe('different');expect(store.pendingOperation.status).toBe('UNKNOWN');expect(store.pendingOperation.playerInput).toBe('');expect(store.pendingOperation.inputReview).toBeUndefined();page.unmount()
 })
 it('ignores stale location and save-point lists after switching sessions',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const locations=deferred(),points=deferred();mocks.get.mockImplementation(path=>path==='/game/world-book'?locations.promise:path==='/game/save-points'?points.promise:ordinaryReads(path))
  const first=page.vm.loadVisitedLocations(),second=page.vm.loadSavePoints();store.setActiveSession({sessionId:'B'})
  locations.resolve({visitedLocations:[{sceneId:'A-secret'}]});points.resolve({currentRound:9,savePoints:[{roundNo:9}]});await Promise.all([first,second])
  expect(page.vm.visitedLocations).toEqual([]);expect(page.vm.savePoints.savePoints).toEqual([]);page.unmount()
 })
 it('keeps the newest locations when an earlier read finishes last',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const old=deferred(),fresh=deferred();let reads=0;mocks.get.mockImplementation(path=>path==='/game/world-book'?(reads++===0?old.promise:fresh.promise):ordinaryReads(path))
  const first=page.vm.loadVisitedLocations(),second=page.vm.loadVisitedLocations();fresh.resolve({visitedLocations:[{sceneId:'latest'}]});await second;old.resolve({visitedLocations:[{sceneId:'obsolete'}]});await first
  expect(page.vm.visitedLocations).toEqual([{sceneId:'latest'}]);page.unmount()
 })
 it('does not install a late restart over another selected session',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  const restart=deferred();mocks.post.mockReturnValue(restart.promise);const pending=page.vm.restartGame(false);store.setActiveSession({sessionId:'B',status:'PLAYING'});mocks.push.mockClear()
  restart.resolve({newSessionId:'new-A',inheritedWorld:false});await pending;expect(store.sessionId).toBe('B');expect(store.status).toBe('PLAYING');expect(mocks.push).not.toHaveBeenCalled();page.unmount()
 })
})

describe('submission recovery after server progress changes',()=>{
 it('reads the committed scene and chapter after a revision conflict, then permits the next deliberate submission',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  try{
   mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve(scene(0,5)):path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:0,revision:5,turns:[{id:31,round:1,actors:[{godId:'narrator',displayedDialogue:'同伴已经离开车站。'}]}]}):ordinaryReads(path))
   mocks.post.mockRejectedValueOnce({response:{data:{code:200009,message:'现场版本已改变，请重新查看后行动'}}})
   page.vm.playerAction='沿着同伴留下的方向前进。';await page.vm.onSubmit();await flushPromises()
   expect(page.vm.scene.revision).toBe(5);expect(page.vm.turns[0].id).toBe('saved-31');expect(page.vm.playerAction).toBe('沿着同伴留下的方向前进。')
   expect(page.vm.actionBlocked).toBe(false);expect(store.pendingOperation).toBeNull();expect(mocks.post).toHaveBeenCalledTimes(1)
   mocks.post.mockImplementation(async(_path,body)=>({operationId:body.operationId,logId:32,round:2,status:'COMPLETED',playerInput:body.playerText,branchEpoch:0,revision:6,scene:scene(0,6),lines:[{godId:'narrator',speech:'主角追上了等在路口的同伴。'}]}))
   await page.vm.onSubmit();await flushPromises()
   expect(mocks.post).toHaveBeenCalledTimes(2);expect(mocks.post.mock.calls[1][1].expectedRevision).toBe(5)
   expect(page.vm.turns.at(-1).actors[0].displayedDialogue).toContain('路口的同伴');expect(page.vm.playerAction).toBe('')
  }finally{page.unmount()}
 })
 it('keeps a retryable synchronization state if the read following a conflict fails',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  try{
   mocks.post.mockRejectedValue({response:{data:{code:200009,message:'现场版本已改变'}}});mocks.get.mockRejectedValue(new Error('server temporarily unavailable'))
   page.vm.playerAction='继续查访。';await page.vm.onSubmit();await flushPromises()
   expect(page.vm.syncRequired).toBe(true);expect(page.vm.actionBlocked).toBe(true);expect(page.vm.playerAction).toBe('继续查访。');expect(page.vm.loading).toBe(false)
   mocks.get.mockImplementation(ordinaryReads);await page.vm.refreshActionResult();await flushPromises()
   expect(page.vm.syncRequired).toBe(false);expect(page.vm.actionBlocked).toBe(false);expect(mocks.post).toHaveBeenCalledTimes(1)
  }finally{page.unmount()}
 })
 it('adopts an already running server operation after a conflict instead of resending the action',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  try{
   mocks.get.mockImplementation(path=>path==='/game/scene'?Promise.resolve({...scene(0,2),pendingOperationId:'accepted-in-other-tab'}):path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:0,revision:2,turns:[]}):ordinaryReads(path))
   mocks.post.mockRejectedValue({response:{data:{code:200009,message:'上一操作尚未完成'}}});page.vm.playerAction='准备下一步。';await page.vm.onSubmit();await flushPromises()
   expect(store.pendingOperation.operationId).toBe('accepted-in-other-tab');expect(page.vm.playerAction).toBe('准备下一步。');expect(page.vm.actionBlocked).toBe(true);expect(mocks.post).toHaveBeenCalledTimes(1)
   mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.resolve({operationId:'accepted-in-other-tab',logId:35,round:1,status:'COMPLETED',playerInput:'原来的行动',branchEpoch:0,revision:3,scene:scene(0,3),lines:[{godId:'narrator',speech:'原来的行动已完成。'}]}):ordinaryReads(path))
   await vi.advanceTimersByTimeAsync(2000);await flushPromises()
   expect(store.pendingOperation).toBeNull();expect(page.vm.actionBlocked).toBe(false);expect(page.vm.playerAction).toBe('准备下一步。');expect(mocks.post).toHaveBeenCalledTimes(1)
  }finally{page.unmount()}
 })
})

describe('automatic read-only operation recovery',()=>{
 it.each(['resolve','reject'])('releases a stale status read after switching perspective when it %ss',async completion=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'view-read',branchEpoch:0,status:'RUNNING',playerInput:'继续调查'});const pendingRead=deferred()
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?pendingRead.promise:ordinaryReads(path))
  const old=page.vm.queryOperation();expect(page.vm.queryingOperation).toBe(true);page.vm.view.setMode('OMNISCIENT')
  if(completion==='resolve')pendingRead.resolve({operationId:'view-read',logId:35,round:1,status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[{godId:'narrator',speech:'旧视角的内容'}]});else pendingRead.reject(new Error('old read unavailable'))
  await old;await flushPromises()
  expect(page.vm.queryingOperation).toBe(false);expect(page.vm.turns).toHaveLength(0);expect(page.vm.actionError).toBe('');expect(store.pendingOperation.operationId).toBe('view-read')
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.resolve({operationId:'view-read',logId:35,round:1,status:'RUNNING',branchEpoch:0,revision:1,scene:{...scene(0,1),viewMode:'OMNISCIENT'},lines:[]}):ordinaryReads(path))
  await page.vm.queryOperation();expect(mocks.get.mock.calls.filter(([path])=>path.startsWith('/game/operations/'))).toHaveLength(2);expect(page.vm.queryingOperation).toBe(false);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('keeps recovery controls and the composer mounted while a read-only poll is in flight',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'stable-read',branchEpoch:0,status:'RUNNING',playerInput:'继续学习本事'});await flushPromises()
  const recovery=page.get('.operation-recovery').element,composer=page.get('.composer-form').element,pendingRead=deferred()
  page.vm.actionError='上次查询暂时未能连通';await flushPromises()
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?pendingRead.promise:ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises()
  expect(page.vm.queryingOperation).toBe(true);expect(page.vm.loading).toBe(false);expect(page.vm.busy).toBe(false)
  expect(page.get('.operation-recovery').element).toBe(recovery);expect(page.get('.composer-form').element).toBe(composer)
  expect(page.text()).toContain('上次查询暂时未能连通')
  expect(page.find('.story-status').exists()).toBe(false);expect(page.text()).toContain('正在查询…')
  pendingRead.resolve({operationId:'stable-read',logId:31,round:1,status:'RUNNING',branchEpoch:0,revision:1,scene:scene(0,1),lines:[]})
  await flushPromises();expect(page.vm.queryingOperation).toBe(false);expect(page.get('.operation-recovery').element).toBe(recovery);expect(page.vm.actionError).toBe('')
  expect(page.get('.composer-form').element).toBe(composer);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('shows one honest recovery notice instead of publishing an empty partial story and a duplicate notice',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'partial',branchEpoch:0,status:'RUNNING',playerInput:'继续学习本事，陪伴老格里姆'})
  page.vm.applyOperation({operationId:'partial',logId:32,round:1,status:'PARTIAL',pendingKind:'RESULT_UNRESOLVED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[],ruleSnapshot:{technicalIssues:[{kind:'STORY_RESULT_UNAVAILABLE'}]}},'A')
  await flushPromises()
  const notice='此段故事尚未生成完成。已取得的人物意愿和已有内容保留，可以续接故事生成。'
  expect(page.text().split(notice)).toHaveLength(2);expect(page.findAll('.operation-recovery')).toHaveLength(1)
  expect(page.get('.operation-recovery').text()).toContain('续接未完成部分');expect(page.get('.operation-recovery').text()).toContain('查询进度')
  expect(page.findAll('.story-chapter')).toHaveLength(0);expect(page.vm.turns).toHaveLength(1)
  expect(page.vm.actionBlocked).toBe(true);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('retains resource-specific remedies when story generation is the incomplete stage',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'story-timeout',branchEpoch:0,status:'RUNNING'})
  page.vm.applyOperation({operationId:'story-timeout',logId:34,round:1,status:'PARTIAL',pendingKind:'MODEL_TIMEOUT',branchEpoch:0,revision:1,scene:scene(0,1),lines:[],ruleSnapshot:{technicalIssues:[{kind:'STORY_RESULT_UNAVAILABLE'}]}},'A')
  await flushPromises();expect(page.get('.operation-recovery').text()).toContain('模型响应超时');expect(page.get('.operation-recovery').text()).not.toContain('人物反应');page.unmount()
 })
 it('keeps already-authored partial prose visible while recovery remains available',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'partial-prose',branchEpoch:0,status:'RUNNING'})
  page.vm.applyOperation({operationId:'partial-prose',logId:33,round:1,status:'PARTIAL',pendingKind:'MODEL_TIMEOUT',branchEpoch:0,revision:1,scene:scene(0,1),lines:[{godId:'narrator',speech:'格里姆示范过一遍，主角跟着学会了收刀的动作。'}]},'A')
  await flushPromises();expect(page.get('.story-chapter').text()).toContain('学会了收刀的动作');expect(page.get('.operation-recovery').text()).toContain('超时');page.unmount()
 })
 it('finds a committed story after losing the submit response without posting the action twice',async()=>{
  vi.useFakeTimers();vi.setSystemTime(100000);mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  mocks.post.mockRejectedValue(new Error('response lost'));page.vm.playerAction='和同伴继续调查。';await page.vm.onSubmit();expect(store.pendingOperation.status).toBe('UNKNOWN');const id=store.pendingOperation.operationId
  expect(mocks.post.mock.calls[0][2].timeout).toBe(20000)
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.resolve({operationId:id,logId:20,round:1,playerInput:'和同伴继续调查。',status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[{godId:'narrator',speech:'你与同伴查清了来历。'}]}):ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises();expect(store.pendingOperation).toBeNull();expect(page.vm.actionBlocked).toBe(false);expect(page.vm.playerAction).toBe('');expect(page.vm.turns.at(-1).actors[0].displayedDialogue).toContain('查清');expect(mocks.post).toHaveBeenCalledTimes(1);page.unmount()
  expect(mocks.get.mock.calls.find(c=>c[0].startsWith('/game/operations/'))[1].timeout).toBe(20000)
 })
 it('keeps a newly edited next action when the original accepted result arrives',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  mocks.post.mockRejectedValue(new Error('response lost'));page.vm.playerAction='原来的行动';await page.vm.onSubmit();const id=store.pendingOperation.operationId;page.vm.playerAction='新准备的下一步'
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.resolve({operationId:id,logId:22,round:1,playerInput:'原来的行动',status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[]}):ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises();expect(page.vm.playerAction).toBe('新准备的下一步');expect(store.pendingOperation).toBeNull();expect(mocks.post).toHaveBeenCalledTimes(1);page.unmount()
 })
 it('backs off failed reads and keeps the elapsed clock across polls',async()=>{
  vi.useFakeTimers();vi.setSystemTime(100000);mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'same',branchEpoch:0,status:'RUNNING',playerInput:'继续调查',startedAt:Date.now()});let queries=0
  mocks.get.mockImplementation(path=>{if(!path.startsWith('/game/operations/'))return ordinaryReads(path);queries++;return queries===1?Promise.reject(new Error('temporary offline')):Promise.resolve({operationId:'same',logId:30,round:1,status:'RUNNING',branchEpoch:0,revision:1,scene:scene(0,1),lines:[]})})
  await vi.advanceTimersByTimeAsync(2000);await flushPromises();expect(queries).toBe(1)
  await vi.advanceTimersByTimeAsync(3999);expect(queries).toBe(1);await vi.advanceTimersByTimeAsync(1);await flushPromises();expect(queries).toBe(2);expect(page.vm.requestSeconds).toBeGreaterThanOrEqual(6);expect(page.text()).toContain('已等待 6 秒');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('stops at a paid-generation pause and never automatically authorizes more model calls',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'limit',branchEpoch:0,status:'RUNNING'})
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.resolve({operationId:'limit',logId:40,round:1,status:'PARTIAL',pendingKind:'MODEL_CALL_LIMIT',branchEpoch:0,revision:1,scene:scene(0,1),lines:[]}):ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises();const reads=mocks.get.mock.calls.filter(c=>c[0].startsWith('/game/operations/')).length
  await vi.advanceTimersByTimeAsync(20000);expect(mocks.get.mock.calls.filter(c=>c[0].startsWith('/game/operations/'))).toHaveLength(reads);expect(store.pendingOperation.status).toBe('PARTIAL');expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
 it('restores input if the automatic read confirms the action was never registered',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();store.setPendingOperation({operationId:'missing',branchEpoch:0,status:'UNKNOWN',playerInput:'沿河找人。'})
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.reject({response:{data:{code:200010}}}):ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises();expect(store.pendingOperation).toBeNull();expect(page.vm.playerAction).toBe('沿河找人。');expect(page.vm.actionBlocked).toBe(false);expect(mocks.post).not.toHaveBeenCalled();page.unmount()
 })
})

describe('unconfirmed submission identity',()=>{
 it('retains an unacknowledged exact request across a refresh without a server pending ID',()=>{
  const submission={operationId:'late',sessionId:'A',branchEpoch:0,playerText:'问候朋友',sceneId:'home',expectedRevision:0}
  const previous={operationId:'late',branchEpoch:0,status:'UNKNOWN',playerInput:submission.playerText,submission}
  expect(reconcilePendingOperation(scene(),previous)?.operationId).toBe('late')
  expect(reconcilePendingOperation(scene(),previous,'B')).toBeNull()
  expect(reconcilePendingOperation(scene(1),previous)).toBeNull()
  expect(reconcilePendingOperation(scene(),{...previous,status:'PARTIAL',pendingKind:'MODEL_TIMEOUT',submission:undefined})).toBeNull()
 })
 it('retries a temporarily missing submission with its exact original identity and preferences, then permits the next action',async()=>{
  vi.useFakeTimers();mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  page.vm.readingPreferences.narrativeMode='LONG';page.vm.playerAction='你为何来这里？'
  mocks.post.mockRejectedValueOnce(new Error('ack lost'))
  await page.vm.onSubmit({targetGodId:'friend',spokenText:'你为何来这里？'},'朋友')
  const original=JSON.parse(JSON.stringify(mocks.post.mock.calls[0][1]))
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.reject({response:{data:{code:200010}}}):ordinaryReads(path))
  await vi.advanceTimersByTimeAsync(2000);await flushPromises()
  expect(store.pendingOperation?.operationId).toBe(original.operationId)
  expect(store.pendingOperation.pendingKind).toBe('SUBMISSION_NOT_REGISTERED')
  expect(page.vm.actionBlocked).toBe(true);expect(mocks.post).toHaveBeenCalledTimes(1)
  page.vm.readingPreferences.narrativeMode='NORMAL'
  mocks.post.mockImplementation((_path,input)=>Promise.resolve({operationId:input.operationId,logId:72,round:1,status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[{godId:'narrator',speech:'朋友说明了来意。'}]}))
  await page.vm.resumeOperation();await flushPromises()
  expect(mocks.post.mock.calls[1][0]).toBe('/game/operations');expect(mocks.post.mock.calls[1][1]).toEqual(original)
  expect(store.pendingOperation).toBeNull();expect(page.vm.actionBlocked).toBe(false);expect(page.vm.turns).toHaveLength(1)
  page.vm.playerAction='接着去见店主。';await page.vm.onSubmit()
  expect(mocks.post.mock.calls[2][1].operationId).not.toBe(original.operationId)
  expect(mocks.post.mock.calls[2][1].narrativeMode).toBe('NORMAL');page.unmount()
 })
 it('does not automatically replay an unregistered request after restoring a tab',async()=>{
  vi.useFakeTimers();const submission={operationId:'restore-late',sessionId:'A',branchEpoch:0,expectedRevision:0,sceneId:'scene',playerText:'继续调查',storyMode:true,narrativeMode:'NORMAL'}
  store.setPendingOperation({operationId:submission.operationId,branchEpoch:0,status:'UNKNOWN',playerInput:submission.playerText,submission})
  mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?Promise.reject({response:{data:{code:200010}}}):ordinaryReads(path))
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises();await vi.advanceTimersByTimeAsync(2000);await flushPromises()
  expect(store.pendingOperation?.submission).toEqual(submission)
  await vi.advanceTimersByTimeAsync(30000);expect(mocks.post).not.toHaveBeenCalled();expect(page.text()).toContain('按原请求重试');page.unmount()
 })
 it('ignores a late missing-status error after a different operation has been synchronized',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  store.setPendingOperation({operationId:'old-read',branchEpoch:0,status:'UNKNOWN',playerInput:'旧行动'})
  const status=deferred();mocks.get.mockImplementation(path=>path.startsWith('/game/operations/')?status.promise:ordinaryReads(path))
  const reading=page.vm.queryOperation();store.setPendingOperation({operationId:'new-operation',branchEpoch:0,status:'RUNNING',playerInput:'新行动'})
  status.reject({response:{data:{code:200010}}});await reading;await flushPromises()
  expect(store.pendingOperation.operationId).toBe('new-operation');expect(page.vm.actionError).toBe('');expect(page.vm.queryingOperation).toBe(false);page.unmount()
 })
})

describe('broad story directions and meaningful human choices',()=>{
 it('asks for a broad action and enables story mode for ordinary input',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises()
  expect(page.find('textarea').attributes('placeholder')).toContain('大致行动方向');expect(page.text()).toContain('推进故事')
  page.vm.playerAction='我去了解朋友的伤情。';mocks.post.mockImplementation((_path,input)=>Promise.resolve({operationId:input.operationId,logId:1,round:1,status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[],ruleSnapshot:{storyProgress:{beats:1,requiresInput:false,reason:'已经自然了解情况。'}}}))
  await page.vm.onSubmit();expect(mocks.post.mock.calls[0][1].storyMode).toBe(true);expect(mocks.post.mock.calls[0][1].dialogue).toBeUndefined();page.unmount()
 })
 it('keeps manually worded conversation explicit instead of adding automatic player lines',async()=>{
  mocks.get.mockImplementation(ordinaryReads);const page=shallowMount(Play,{global:globalOptions()});await flushPromises();page.vm.playerAction='你为何受伤？'
  mocks.post.mockImplementation((_path,input)=>Promise.resolve({operationId:input.operationId,logId:1,round:1,status:'COMPLETED',branchEpoch:0,revision:1,scene:scene(0,1),lines:[]}))
  await page.vm.onSubmit({targetGodId:'friend',spokenText:'你为何受伤？'},'朋友');expect(mocks.post.mock.calls[0][1].storyMode).toBe(false);expect(mocks.post.mock.calls[0][1].dialogue.spokenText).toBe('你为何受伤？');page.unmount()
 })
 it('restores a major relationship decision with a choice prompt rather than a routine talking prompt',async()=>{
  mocks.get.mockImplementation(path=>path==='/game/transcript'?Promise.resolve({sessionId:'A',branchEpoch:0,revision:0,turns:[{id:1,actors:[],storyProgress:{requiresInput:true,decisionType:'ROMANCE',reason:'她向你表白，你愿意如何回应？'}}]}):ordinaryReads(path))
  const page=shallowMount(Play,{global:globalOptions()});await flushPromises();expect(page.text()).toContain('此刻，你如何回应');expect(page.text()).toContain('她向你表白');expect(page.find('textarea').attributes('placeholder')).toContain('重要时刻');page.unmount()
 })
})
