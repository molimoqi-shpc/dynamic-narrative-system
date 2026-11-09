import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import Analysis from '../src/components/EngineDemoAnalysis.vue'

const labels={zhi:'知',hui:'慧',dou:'斗',wen:'闻',ben:'本',gui:'归'}
function book(actualDou=777){
 return {common:['C(x)遵循服务端成长曲线。'],stats:Object.entries(labels).map(([key,label],index)=>({key,label,description:label+'的实际用途',formula:'实际'+label+'=round(C('+label+')×M×协同)',base:index+10,actual:key==='dou'?actualDou:index+20,growthValue:137.123456,masteryMultiplier:1.234567,synergy:0.456789,synergyMultiplier:1.13579,supportNames:['慧','归'],supportBases:[37,15],unrounded:986.54321}))}
}
function fixture(){
 return {
  process:[{title:'角色提出意愿',detail:'角色只依据自己所知作出选择。'},{title:'主创生成经过',detail:'处置由主创结合人物意愿写出。'}],
  behaviors:[{id:'hero',name:'沈砚',goal:'逼对方表态',known:'目前没有实证',choice:'先行质问',risk:'被要求举证',consequence:'失去主动权',alternative:'先收集线索'}],
  dimensions:Object.entries(labels).map(([key,label])=>({key,label,meaning:label+'的含义',sceneUse:label+'的故事用途',combatUse:label+'的战斗用途',boundary:label+'不会强令人物服从'})),
  profiles:[{id:'hero',name:'沈砚',formulaBook:book(777)},{id:'rain',name:'雨神',formulaBook:book(888)}],
  combatRule:{difference:'D=攻方实际斗−守方实际斗',threshold:'T=max(10,round(0.1×双方实际斗之和))',decision:'D≥T 攻方胜；D≤−T 守方胜；否则平局'},
  combatCases:Array.from({length:5},(_,index)=>({id:'case-'+index,title:'交手案例'+(index+1),context:'第'+(index+1)+'种交锋环境',attacker:{name:'攻方甲'+index,formulaBook:book(123+index)},defender:{name:'守方乙'+index,formulaBook:book(456+index)},comparison:{actorActualDou:123+index,targetActualDou:456+index,difference:-301-index,threshold:13+index,outcome:'DEFENDER_WIN',outcomeLabel:'守方胜',differenceFormula:'案例'+(index+1)+'的差值代入',thresholdFormula:'案例'+(index+1)+'的阈值代入'},interpretation:'案例'+(index+1)+'的后果由角色意愿与主创决定。',statDeltas:[{name:'攻方甲'+index,changes:{dou:-2,wen:-1}},{name:'守方乙'+index,changes:{dou:2,wen:1}}]})),
  consequences:[{title:'能力变化',detail:'基础变化与实际值重新结算。'}],
  boundaries:['战斗只结算胜负，胜者处置意愿交给主 API 生成后续。','旧丰都干预公式仅作示例，不强迫当前故事的角色出手。']
 }
}
const clickButton=async(view,label)=>view.findAll('button').find(button=>button.text()===label).trigger('click')
describe('detailed behavior analysis',()=>{
 it('starts with the complete behavior process and preserves the current and legacy rule boundaries',()=>{
  const data=fixture(),before=JSON.stringify(data),view=mount(Analysis,{props:{analysis:data}})
  expect(view.get('[data-analysis-panel="behavior"]').text()).toContain('角色提出意愿')
  const behavior=view.get('.behavior-card').text()
  for(const phrase of ['逼对方表态','目前没有实证','先行质问','被要求举证','失去主动权','先收集线索'])expect(behavior).toContain(phrase)
  expect(view.get('.analysis-boundaries').text()).toContain('战斗只结算胜负')
  expect(view.get('.analysis-boundaries').text()).toContain('不强迫当前故事的角色出手')
  expect(view.findAll('button').map(button=>button.attributes('aria-pressed'))).toEqual(['true','false','false'])
  expect(JSON.stringify(data)).toBe(before);view.unmount()
 })
 it('shows six concrete uses and profile-specific service values plus formula factors without recalculating them',async()=>{
  const data=fixture(),before=JSON.stringify(data),view=mount(Analysis,{props:{analysis:data}})
  await clickButton(view,'六维实际应用')
  expect(view.findAll('.dimension-cards>article')).toHaveLength(6)
  expect(view.findAll('.profile-formula .stats-table tbody tr')).toHaveLength(6)
  expect(view.get('.profile-formula .stats-table').text()).toContain('777')
  expect(view.get('.formula-details').text()).toContain('137.1235')
  expect(view.get('.formula-details').text()).toContain('986.5432')
  expect(view.get('.formula-details').text()).toContain('慧 37 · 归 15')
  expect(view.findAll('.formula-details details').every(detail=>detail.attributes('open')===undefined)).toBe(true)
  await view.get('.profile-formula select').setValue('rain')
  expect(view.get('.profile-formula .stats-table caption').text()).toBe('雨神的六维')
  expect(view.get('.profile-formula .stats-table').text()).toContain('888')
  expect(view.get('.profile-formula .stats-table').text()).not.toContain('777')
  expect(JSON.stringify(data)).toBe(before);view.unmount()
 })
 it('switches among all combat examples and uses returned thresholds, outcomes and deltas rather than inventing combat calculations',async()=>{
  const data=fixture(),before=JSON.stringify(data),view=mount(Analysis,{props:{analysis:data}})
  await clickButton(view,'战斗逻辑')
  expect(view.get('[aria-label="选择交手案例"]').findAll('option')).toHaveLength(5)
  expect(view.get('.combat-example-notice').text()).toContain('不是当前故事已发生的战斗')
  expect(view.get('.combat-rule').text()).toContain('D=攻方实际斗−守方实际斗')
  expect(view.get('.combat-rule').text()).toContain('D≥T 攻方胜')
  expect(view.get('.combat-substitution').text()).toContain('案例1的差值代入')
  expect(view.get('.combat-substitution').text()).toContain('案例1的阈值代入')
  expect(view.get('.combat-delta-title').text()).toContain('实际交手并保存后才生效')
  expect(view.get('.combat-consequences').text()).toContain('基础变化与实际值重新结算。')
  expect(view.get('.combat-comparison').text()).toContain('攻守差值-301')
  expect(view.get('.combat-comparison').text()).toContain('胜负阈值 T13')
  expect(view.findAll('.combat-fighter .stats-table tbody tr')).toHaveLength(12)
  expect(view.findAll('.combat-dou-formula')).toHaveLength(2)
  expect(view.get('.combat-deltas').text()).toContain('斗 -2闻 -1')
  await view.get('[aria-label="选择交手案例"]').setValue('case-4')
  expect(view.get('.combat-title').text()).toBe('交手案例5')
  expect(view.get('.combat-substitution').text()).toContain('案例5的差值代入')
  expect(view.get('.combat-substitution').text()).toContain('案例5的阈值代入')
  expect(view.get('.combat-substitution').text()).not.toContain('案例1的')
  expect(view.get('.combat-comparison').text()).toContain('攻方实际斗127')
  expect(view.get('.combat-comparison').text()).toContain('攻守差值-305')
  expect(view.get('.combat-comparison').text()).toContain('胜负阈值 T17')
  expect(view.get('.combat-comparison').text()).toContain('守方胜')
  expect(view.get('.combat-interpretation').text()).toContain('案例5的后果')
  expect(view.get('.combat-fighters').text()).not.toContain('攻方甲0')
  expect(JSON.stringify(data)).toBe(before);view.unmount()
 })
})



