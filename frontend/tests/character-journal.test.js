import {describe,it,expect,beforeEach,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useSessionStore} from '../src/store/session.js'
const mocks=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mocks.get}}))
vi.mock('vue-router',()=>({useRoute:()=>({path:'/play'})}))
import Panel from '../src/components/CharacterPanel.vue'
const stats={zhi:20,hui:20,dou:20,wen:20,ben:20,gui:20}
const profile=()=>({name:'林舟',status:'PLAYING',location:'渡口',round:8,health:100,emotion:'平静',actualStats:stats,baseStats:stats,inventory:['木工凿','麻绳','月魄镜'],importantItems:[{ref:'mirror',name:'月魄镜',kind:'ARTIFACT',description:'银背旧镜',knownEffects:'照见石壁残留灵光',significance:'旧案的关键线索',origin:'知秋交付',limitations:'仅显示微弱残迹'}],friends:[{godId:'ally',name:'知秋',label:'同行盟友',summary:'已约定一起追查旧案',basis:'双方实际表态结盟'}],enemies:[{godId:'enemy',name:'厉川',label:'公开敌对',summary:'曾在渡口追杀你',basis:'亲历冲突'}],acquaintances:[{godId:'known',name:'船夫',label:'曾经问路',basis:'实际问路所得'}]})
let store,pinia
beforeEach(()=>{pinia=createPinia();setActivePinia(pinia);store=useSessionStore();store.setActiveSession({playerId:'u',sessionId:'A',status:'PLAYING',branchEpoch:0});mocks.get.mockReset();mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'A',status:'PLAYING'}]}:profile()))})
function create(){return mount(Panel,{global:{plugins:[pinia],stubs:{ElDrawer:{props:['modelValue'],template:'<aside v-if="modelValue"><slot /></aside>'},ElButton:{template:'<button><slot /></button>'},StatFormulaDialog:true}}})}
async function open(page){await page.find('[aria-label="查看自己的人物面板"]').trigger('click');await flushPromises()}
async function tab(page,name){await page.findAll('.profile-tabs button').find(b=>b.text()===name).trigger('click')}
describe('character journal experience',()=>{
 it('shows a supported situational talent and its boundary without the old unimplemented warning',async()=>{
  mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'A',status:'PLAYING'}]}:{...profile(),talents:[{name:'听木知隙',description:'【情境天赋】近距离轻敲木器时更容易察觉空腔；不能隔墙获知内部，嘈杂会干扰判断。',effectKind:'NARRATIVE',power:2}]}))
  const page=create();try{await open(page);expect(page.text()).toContain('听木知隙');expect(page.text()).toContain('不能隔墙');expect(page.text()).not.toContain('尚未绑定')}finally{page.unmount()}
 })
 it('keeps the warning on an unsupported legacy talent',async()=>{
  mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'A',status:'PLAYING'}]}:{...profile(),talents:[{name:'旧版记录',description:'过往未实现的特殊承诺',effectKind:'LEGACY',power:2}]}))
  const page=create();try{await open(page);expect(page.text()).toContain('尚未绑定')}finally{page.unmount()}
 })
 it('opens the current save’s item in the world book with its literal name and no mutation',async()=>{
  const requested=vi.fn();window.addEventListener('open-world-book',requested)
  const page=create();try{await open(page);await tab(page,'行囊珍藏');const count=mocks.get.mock.calls.length;await page.find('.folio-consult-link').trigger('click')
   expect(requested).toHaveBeenCalledTimes(1);expect(requested.mock.calls[0][0].detail).toEqual({sessionId:'A',keyword:'月魄镜'});expect(mocks.get).toHaveBeenCalledTimes(count)
  }finally{page.unmount();window.removeEventListener('open-world-book',requested)}
 })
 it('does not send another save’s inventory into the currently played world book',async()=>{
  mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'B',status:'PLAYING'}]}:profile()))
  const page=create();await open(page);await tab(page,'行囊珍藏');expect(page.find('.relic-detail').text()).toContain('月魄镜');expect(page.find('.folio-consult-link').exists()).toBe(false);page.unmount()
 })
 it('opens the requested inventory tab from the new toolbar and keeps retired saves readable',async()=>{
  store.status='RETIRED';mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'A',status:'RETIRED'}]}:{...profile(),status:'RETIRED'}))
  const page=create();window.dispatchEvent(new CustomEvent('open-character-panel',{detail:{tab:'items'}}));await flushPromises()
  expect(page.text()).toContain('此生归隐');expect(page.find('.profile-tabs button[aria-pressed=true]').text()).toBe('行囊珍藏');expect(page.text()).toContain('月魄镜');page.unmount()
 })
 it('uses the protagonist name and explains actual consequences without fixed defeat damage',async()=>{const page=create();await open(page);expect(page.find('h2').text()).toBe('林舟');expect(page.text()).not.toContain('败方损失25');expect(page.text()).toContain('切磋可以点到为止');page.unmount()})
 it('shows meaningful items, known function and provenance without ordinary clutter or fake empty slots',async()=>{const page=create();await open(page);await tab(page,'行囊珍藏');expect(page.text()).toContain('月魄镜');expect(page.text()).toContain('照见石壁残留灵光');expect(page.text()).toContain('知秋交付');expect(page.text()).not.toContain('木工凿');expect(page.text()).not.toContain('麻绳');expect(page.find('.inventory-empty').exists()).toBe(false);expect(page.findAll('.relic-tile')).toHaveLength(1);page.unmount()})
 it('searches by known story meaning and filters categories with honest empty results',async()=>{const page=create();await open(page);await tab(page,'行囊珍藏');await page.find('[aria-label="搜索珍藏"]').setValue('旧案');expect(page.findAll('.relic-tile')).toHaveLength(1);await page.findAll('.collection-filters button').find(b=>b.text()==='装备').trigger('click');expect(page.text()).toContain('未找到符合条件的珍藏');expect(page.find('.relic-detail').exists()).toBe(false);page.unmount()})
 it('renders actual allies, enemies and acquaintances with their relationship basis',async()=>{const page=create();await open(page);await tab(page,'人物牵系');for(const name of ['知秋','厉川','船夫'])expect(page.text()).toContain(name);expect(page.text()).toContain('双方实际表态结盟');expect(page.text()).not.toContain('信任≥70');page.unmount()})
 it('does not mistake an old raw inventory for meaningful artifacts',async()=>{mocks.get.mockImplementation(path=>Promise.resolve(path==='/game/sessions'?{sessions:[{sessionId:'A',status:'PLAYING'}]}:{...profile(),importantItems:[]}));const page=create();await open(page);await tab(page,'行囊珍藏');expect(page.text()).toContain('还没有记下重要珍藏');expect(page.findAll('.relic-tile')).toHaveLength(0);page.unmount()})
 it('invalidates a late profile after switching save or rewind branch',async()=>{let resolve;mocks.get.mockImplementation(path=>path==='/game/sessions'?Promise.resolve({sessions:[{sessionId:'A',status:'PLAYING'}]}):new Promise(r=>{resolve=r}));const page=create();await page.find('[aria-label="查看自己的人物面板"]').trigger('click');await flushPromises();store.branchEpoch=1;resolve({...profile(),name:'旧分支人物'});await flushPromises();expect(page.text()).not.toContain('旧分支人物');expect(page.find('aside').exists()).toBe(false);page.unmount()})
})
