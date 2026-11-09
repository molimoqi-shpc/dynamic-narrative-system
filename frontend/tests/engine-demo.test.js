import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
const api=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),error:vi.fn()}))
vi.mock('../src/api',()=>({default:api}))
vi.mock('element-plus',async original=>({...await original(),ElMessage:{error:api.error}}))
import Demo from '../src/views/EngineDemoView.vue'
let view
function reply(){
 return {
  outcomeType:'EXPLOITATION_RESCUE',outcomeLabel:'算计救援',lines:[],lieFlags:{museum_god:true},
  factsChinese:['原始事实：博物神实际参与了干预'],token:{total:0,costCny:0},
  ruleSnapshot:{
   statDeltaPerGod:{player_01:{zhi:0,hui:0,dou:0,wen:-2,ben:0,gui:0},museum_god:{zhi:0,hui:0,dou:0,wen:2,ben:0,gui:1}},
   before_actuals_per_god:{player_01:{wen:14},museum_god:{wen:31,gui:10}},
   after_actuals_per_god:{player_01:{wen:11},museum_god:{wen:34,gui:11}},
   relationshipDeltas:[{sourceId:'player_01',targetId:'museum_god',delta:-8}],
   initialRelationships:[{sourceId:'museum_god',targetId:'rain_god',trust:30,backstabbed:true},{sourceId:'rain_god',targetId:'museum_god',trust:10,backstabbed:false}],
   interveners:[{intervenerId:'museum_god',supports:'rain_god',ability:15.3,will:28.2,gain:14,total:57.5}]
  },
  analysis:{process:[{title:'从所知形成意愿',detail:'主创结合意愿与属性写过程。'}],behaviors:[],dimensions:[],profiles:[],combatCases:[],boundaries:['交手结果不决定胜者处置。']},
  story:{
   title:'雨中借援夺势',premise:'连月阴雨，沈砚与两位神明在城中相遇。',
   cast:[{id:'player_01',name:'沈砚',identity:'行旅者',publicGoal:'追问涝灾',hiddenMotive:'想借指控逼雨神让步'},{id:'rain_god',name:'雨神',identity:'被质问的人',publicGoal:'要求证据',hiddenMotive:'不愿轻易退让'},{id:'museum_god',name:'博物神',identity:'在场旁观者',publicGoal:'劝止争执',hiddenMotive:'夺取双方话语权'}],
   beats:[
    {id:'one',stage:'缘起',speakerId:'player_01',speakerName:'沈砚',action:'沈砚收起伞，站到檐下。',speech:'这雨究竟是谁的责任？',innerThought:'我想逼他先表态。',explanation:'指控尚无证据。'},
    {id:'two',stage:'当面对峙',speakerId:'rain_god',speakerName:'雨神',speech:'指责之前，把证据拿出来。'},
    {id:'three',stage:'当面对峙',speakerId:'player_01',speakerName:'沈砚',speech:'城中积水还不能说明问题？'},
    {id:'four',stage:'借援夺势',speakerId:'museum_god',speakerName:'博物神',action:'博物神抬手隔开二人。',speech:'两位，不妨把话说清。',innerThought:'让他们都先听我说。',explanation:'他开始控制争论的顺序。'},
    {id:'five',stage:'借援夺势',speakerId:'rain_god',speakerName:'雨神',speech:'让他回答。'},
    {id:'six',stage:'借援夺势',speakerId:'museum_god',speakerName:'博物神',speech:'就从刚才的指控说起。'},
    {id:'seven',stage:'结果余波',speakerId:'player_01',speakerName:'沈砚',speech:'这件事，我还会查。'},
    {id:'eight',stage:'结果余波',speakerId:'rain_god',speakerName:'雨神',speech:'查清再来。'},
    {id:'nine',stage:'结果余波',speakerId:'museum_god',speakerName:'博物神',speech:'我没出手，此事与我无关。',innerThought:'把得利藏在劝解之后。',lieReason:'他刚才改变了双方交锋的进程。'}
   ],ending:'沈砚受挫，博物神从争执中得利。'
  }
 }
}
beforeEach(()=>{
 api.get.mockReset().mockImplementation(path=>Promise.resolve(path.includes('health')?{ok:true}:{actual_stat_formula:'六维计算',resolution_priority_order:['P2 BACKSTAB exploitation_rescue','P1 TALISMAN_NEUTRALIZE']}))
 api.post.mockReset();api.error.mockReset()
 view=mount(Demo,{global:{stubs:{ElButton:{props:['loading'],template:'<button :disabled="loading"><slot/></button>'}},mocks:{$router:{push:vi.fn()}}}})
})
afterEach(()=>view.unmount())
const run=()=>view.get('[data-testid="run-walkthrough"]').trigger('click')
describe('isolated rules walkthrough',()=>{
 it('renders the staged cast and dialogue with one per-speech disclosure and unchanged engine numbers',async()=>{
  const data=reply(),original=JSON.stringify(data);api.post.mockResolvedValue(data)
  await run();await flushPromises()
  expect(api.post).toHaveBeenCalledWith('/engine/demo/fengdu-walkthrough',{})
  expect(view.get('a.demo-analysis-jump').attributes('href')).toBe('#behavior-analysis-title')
  expect(view.findAll('.demo-person')).toHaveLength(3);expect(view.findAll('.demo-scene')).toHaveLength(4);expect(view.findAll('.demo-scenes .demo-beat')).toHaveLength(9)
  expect(view.findAll('.demo-lie-badge')).toHaveLength(1)
  expect(view.get('.demo-beat-revealed').text()).toContain('我没出手，此事与我无关。')
  const stats=view.get('.demo-stat-changes').text()
  expect(stats).toContain('闻-2实际值 14 → 11');expect(stats).toContain('闻+2实际值 31 → 34');expect(stats).toContain('归+1实际值 10 → 11')
  expect(view.get('.demo-relations').text()).toContain('沈砚 → 博物神信任 -8')
  expect(view.text()).toContain('博物神 → 雨神：信任 30');expect(view.text()).toContain('雨神 → 博物神：信任 10')
  expect(view.get('.demo-evidence').attributes('open')).toBeUndefined()
  expect(view.get('.demo-priority').text()).toContain('高阶算计救援');expect(view.get('.demo-priority').text()).not.toContain('P2')
  expect(JSON.stringify(data)).toBe(original)
  expect(view.get('h1').text()).toBe('人物行为解析模版')
  expect(view.text()).not.toMatch(/固定模板|固定示例|预设素材|种子数据|tokens|¥|不消耗 API/)
 })
 it('switches to public actions and dialogue without leaking hidden stage names, motives, result or lie flags',async()=>{
  const data=reply();api.post.mockResolvedValue(data);await run();await flushPromises()
  await view.get('[role="switch"]').setValue(false)
  const publicText=view.text()
  for(const hidden of [data.story.title,data.story.ending,'夺取双方话语权','我想逼他先表态。','他刚才改变了双方交锋的进程。','借援夺势','算计救援','原始事实'])expect(publicText).not.toContain(hidden)
  expect(view.find('.demo-evidence').exists()).toBe(false);expect(view.find('.demo-aftermath').exists()).toBe(false);expect(view.find('.demo-lie-badge').exists()).toBe(false)
  expect(view.find('.demo-analysis-jump').exists()).toBe(false)
  expect(view.find('.behavior-analysis').exists()).toBe(false);expect(publicText).not.toContain('从所知形成意愿');
  expect(publicText).toContain('第三人的劝解');expect(publicText).toContain('指责之前，把证据拿出来。');expect(publicText).toContain('博物神抬手隔开二人。')
  await view.get('[role="switch"]').setValue(true)
  expect(view.get('.behavior-analysis').text()).toContain('从所知形成意愿');
  expect(view.text()).toContain('夺取双方话语权');expect(view.findAll('.demo-lie-badge')).toHaveLength(1);expect(api.post).toHaveBeenCalledTimes(1)
 })
 it('keeps older responses readable with Chinese speaker names without labelling every line from a flagged character as a lie',async()=>{
  api.post.mockResolvedValue({outcomeLabel:'算计救援',lines:[{godId:'museum_god',speech:'先说清楚。'},{godId:'museum_god',speech:'我听着。'},{godId:'unknown_role',speech:'旁观发言'}],lieFlags:{museum_god:true}})
  await run();await flushPromises()
  expect(view.findAll('.demo-legacy-lines .demo-beat')).toHaveLength(3)
  expect(view.findAll('.demo-speaker').map(node=>node.text())).toEqual(['博物神','博物神','unknown_role'])
  expect(view.find('.demo-lie-badge').exists()).toBe(false)
 })
 it('prevents duplicate requests and allows retry after a visible failure',async()=>{
  let reject;api.post.mockReturnValueOnce(new Promise((yes,no)=>{reject=no}))
  await run();await run();expect(api.post).toHaveBeenCalledTimes(1)
  reject({response:{data:{message:'请重新登录'}}});await flushPromises()
  expect(view.get('[role="alert"]').text()).toContain('请重新登录');expect(view.get('[data-testid="run-walkthrough"]').attributes('disabled')).toBeUndefined()
  api.post.mockResolvedValue(reply());await run();await flushPromises()
  expect(api.post).toHaveBeenCalledTimes(2);expect(view.find('[role="alert"]').exists()).toBe(false);expect(view.findAll('.demo-scenes .demo-beat')).toHaveLength(9)
 })
})



