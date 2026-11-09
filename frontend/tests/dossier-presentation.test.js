import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import Dossier from '../src/components/DossierFields.vue'

const history='十二岁，爱法诺的妹妹。她今早下滩找旧币，想替母亲凑药钱。'
describe('readable saved character dossiers',()=>{
 it('presents selected talents as readable effects while hiding author-only usage instructions',()=>{
  const view=mount(Dossier,{props:{value:{identity:{talents:[{name:'听木知隙',description:'近距离检查木器时更易察觉空腔，不能隔墙观察。',effectKind:'NARRATIVE',power:2}],talentMeaning:'INTERNAL_TALENT_INSTRUCTIONS'}}}})
  try{expect(view.text()).toContain('听木知隙');expect(view.text()).toContain('不能隔墙');expect(view.text()).toContain('情境特长');expect(view.text()).not.toMatch(/NARRATIVE|effectKind|talentMeaning|INTERNAL_TALENT/)}finally{view.unmount()}
 })
 it.each([
  JSON.stringify({name:'奈雅',personalHistory:history,source:'OPENING_AUTHOR'}),
  JSON.stringify(JSON.stringify({name:'奈雅',personalHistory:history,source:'OPENING_AUTHOR'})),
  '```json\n'+JSON.stringify({name:'奈雅',personalHistory:history,source:'OPENING_AUTHOR'})+'\n```'
 ])('presents an encoded personal profile as labeled prose: %s',encoded=>{
  const value={identity:{establishedSelf:[encoded]}}
  const view=mount(Dossier,{props:{value}})
  try{expect(view.text()).toContain(history);expect(view.text()).toContain('人物来历');expect(view.text()).not.toMatch(/personalHistory|OPENING_AUTHOR|```|\{"|\\"/);expect(value.identity.establishedSelf[0]).toBe(encoded)}finally{view.unmount()}
 })
 it('omits wire identifiers and prompt instructions while retaining the actual personality and history',()=>{
  const view=mount(Dossier,{props:{value:{templateVersion:'CHARACTER_V2',id:'npc_1234567890',freshnessMeaning:'INTERNAL_SNAPSHOT_INSTRUCTION',psychologyMeaning:'INTERNAL_MODEL_INSTRUCTION',psychology:{personality:{stableTraits:['倔强','顾念家人']}},pastInformation:{ownRecords:[{source_ref:'scene-direction:operation:0',source_type:'SELF_PROFILE',content:JSON.stringify({personalHistory:history})}]}}}})
  try{expect(view.text()).toContain(history);expect(view.text()).toContain('顾念家人');expect(view.text()).not.toMatch(/CHARACTER_V2|npc_1234567890|INTERNAL_|source_ref|SELF_PROFILE/)}finally{view.unmount()}
 })
 it('uses supplied character and item names instead of displaying reference IDs',()=>{
  const view=mount(Dossier,{props:{names:{npc_1234567890:'米拉·萨恩',item_1234567890:'新版潮历'},value:{relationships:[{roleId:'npc_1234567890',trust:'愿意听她劝告'}],itemIntentions:[{itemRef:'item_1234567890',intention:'使用',conditions:'贴在寮外显眼处'}]}}})
  try{expect(view.text()).toContain('米拉·萨恩');expect(view.text()).toContain('新版潮历');expect(view.text()).toContain('贴在寮外显眼处');expect(view.text()).not.toMatch(/npc_1234567890|item_1234567890|roleId|itemRef/)}finally{view.unmount()}
 })
 it('keeps false and zero values, translates recorded state, and preserves ordinary quoted prose',()=>{
  const prose='她写下“{旧誓未了}”，合上册子。'
  const view=mount(Dossier,{props:{value:{body:{awake:false,bound:false,health:0},presence:'LAST_SEEN',personalHistory:prose}}})
  try{expect(view.text()).toContain('否');expect(view.text()).toContain('0');expect(view.text()).toContain('最近所见');expect(view.text()).toContain(prose)}finally{view.unmount()}
 })
 it('retains a readable field added by a later schema without exposing its English key',()=>{
  const view=mount(Dossier,{props:{value:{customBiography:'她曾在盐坞替人核对潮历。'}}})
  try{expect(view.text()).toContain('她曾在盐坞');expect(view.text()).not.toContain('customBiography')}finally{view.unmount()}
 })
 it('does not dump malformed encoded records onto the character page',()=>{
  const raw='{"personalHistory":"尚未闭合'
  const view=mount(Dossier,{props:{value:raw}})
  try{expect(view.text()).not.toContain(raw);expect(view.text()).toContain('暂未整理成可读内容')}finally{view.unmount()}
 })
 it('turns archived knowledge context blocks into readable entries without retrieval headers or portrait codes',()=>{
  const context='【记录序号0（非故事时刻）；来源：STORY_CLOCK】当前故事时点：'+JSON.stringify({year:0,day:1,period:'BEFORE_DAWN',precision:'PERIOD'})+'\n【记录序号0（非故事时刻）；来源：APPEARANCE】玩家已知人物与珍藏：'+JSON.stringify({name:'米拉·萨恩',gender:'female',ageGroup:'YOUNG',species:'HUMAN',culturalStyle:'WESTERN',archetype:'OATH_CLERK'})
  const view=mount(Dossier,{props:{value:{pastInformation:{ownKnowledge:context}}}})
  try{expect(view.text()).toContain('米拉·萨恩');expect(view.text()).toContain('女性');expect(view.text()).toContain('人类');expect(view.text()).toContain('破晓前');expect(view.text()).not.toMatch(/\{"|记录序号|STORY_CLOCK|APPEARANCE|OATH_CLERK|WESTERN|ageGroup/)}finally{view.unmount()}
 })
 it('preserves a literal character name that is also a time-period enum',()=>{
  const view=mount(Dossier,{props:{value:{name:'DAWN',startMoment:{period:'DAWN'}}}})
  try{expect(view.text()).toContain('DAWN');expect(view.text()).toContain('黎明')}finally{view.unmount()}
 })
 it('escapes markup after decoding the saved profile',()=>{
  const text='<img src=x onerror=alert(1)>'
  const view=mount(Dossier,{props:{value:JSON.stringify({personalHistory:text})}})
  try{expect(view.text()).toContain(text);expect(view.find('img,script,[onerror]').exists()).toBe(false);expect(view.text()).not.toContain('personalHistory')}finally{view.unmount()}
 })
 it('bounds deeply nested old records without printing raw objects',()=>{
  let value={personalHistory:'旧记录最深处'}
  for(let i=0;i<35;i++)value={content:value}
  const view=mount(Dossier,{props:{value}})
  try{expect(view.findAll('.dossier-fields').length).toBeLessThanOrEqual(17);expect(view.text()).toContain('更多细节暂不展开');expect(view.text()).not.toContain('[object Object]')}finally{view.unmount()}
 })
})
