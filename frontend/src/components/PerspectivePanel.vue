<template>
 <section class="perspective-panel" aria-label="天眼观察台">
  <header class="observer-frontispiece"><TheatreGlyph name="eye"/><div><p>天眼 · 众生卷宗</p><h2>众生各有所求</h2><p>{{data?.perspectiveNote || '翻阅已保存的人物、意愿与过往。此处的查阅不推进故事。'}}</p></div></header>
  <p v-if="viewMode!=='OMNISCIENT'" role="status">入梦模式已关闭幕后资料，请从世诰查阅自己的见闻。</p>
  <template v-else>
   <div class="observer-layout"><aside class="observer-index" aria-label="卷宗人物索引"><p>人物索引 <span>{{data?.roles?.length || '—'}}</span></p><button v-for="role in data?.roles || []" :key="role.id" :disabled="loading" :aria-pressed="data?.character.id===role.id" @click="chooseRole(role.id)"><span>{{role.name.slice(0,1)}}</span><span>{{role.name}}</span><i>›</i></button><div v-if="!data" class="observer-index-pending"><TheatreGlyph name="book"/><span>{{loading?'正在取卷':'等待卷宗'}}</span></div><footer><TheatreGlyph name="eye"/><span>人心各有所藏<br>所行自有留痕</span></footer></aside><div class="observer-document">
   <div class="observer-controls"><label>查阅角色 <select aria-label="查阅角色" v-model="selected" :disabled="loading" @change="resetMemory"><option value="">主角</option><option v-for="role in data?.roles || []" :key="role.id" :value="role.id">{{role.name}}</option></select></label><button :disabled="loading" @click="load()">刷新资料</button><span v-if="data">{{storyMoment(data.storySeconds)}}</span></div>
   <div v-if="loading || error" class="observer-state" :role="error?'alert':'status'"><TheatreGlyph name="eye"/><h3>{{loading?'正在展开卷宗':'卷宗尚未展开'}}</h3><p>{{loading?'正在读取已保存的人物资料与亲历记录。':error}}</p><button v-if="error" :disabled="loading" @click="load()">重新取卷</button></div>
   <template v-if="data">
    <nav class="observer-tabs" aria-label="天眼资料分类"><button v-for="item in tabs" :key="item.key" :aria-pressed="tab===item.key" @click="tab=item.key">{{item.label}}</button></nav>
    <section v-if="tab==='character'" aria-label="角色真实资料">
     <header class="observer-person-heading"><div class="observer-portrait"><img v-if="portrait.src" :src="portrait.src" :style="portrait.framing?.thumbnail" alt="" /><span v-else>{{data.character.name.slice(0,1)}}</span></div><div><p class="observer-person-kicker">人物卷宗 · {{data.character.tier==='PLAYER'?'此身':'众生'}}</p><h3>{{data.character.name}} <small>{{data.character.tier==='PLAYER'?'主角':data.character.tier==='CORE'?'主要角色':'普通人物'}}</small></h3><p class="observer-person-source">{{data.character.location.map(p=>p.location_name).join('、') || '所在未记录'}}</p><span class="observer-record-stamp">已归档</span></div></header>
     <p class="observer-note">实际所在：{{data.character.location.map(p=>p.location_name).join('、') || '尚未记录'}}。位置是已保存状态，不表示主角知道其行踪。</p>
     <p v-if="data.character.health!=null">当前生命 {{data.character.health}} / 100<template v-if="data.character.body"> · {{data.character.body.awake===false?'未清醒':'清醒'}} · {{data.character.body.bound?'受束缚':'未受束缚'}}<span v-if="data.character.body.hidden"> · 隐蔽中</span></template></p><template v-if="data.character.sixStatsEstablished"><div class="observer-stats"><button v-for="stat in statDefinitions" :key="stat.key" @click="formulaKey=stat.key;formulaVisible=true"><span>{{stat.label}}</span><strong>{{data.character.actualStats[stat.key]}}</strong><small>基础 {{data.character.baseStats[stat.key]}}</small></button></div><p class="observer-note">当前实际值 · 点击查看公式与修正。</p></template><p v-else class="observer-note">具体六维尚未确立，不将数据库占位数当成真实实力。</p>
     <details><summary>实际持物 {{data.character.inventory.length}}</summary><ul v-if="data.character.inventory.length"><li v-for="item in data.character.inventory" :key="item.ref"><strong>{{item.name}}</strong> · {{item.source}}<span v-if="item.ownerName!==item.holderName"> · 原所有者：{{item.ownerName}}</span></li></ul><p v-else>没有已记录的持物。</p></details>
     <h4>{{data.character.tier==='EXTRA'?'现场简表':'角色附加表'}}</h4><p class="observer-note"><template v-if="data.character.dossierSnapshotSeconds!=null">归档于{{storyMoment(data.character.dossierSnapshotSeconds)}}。</template>附加表是当时的人设、心理和意愿，可能早于当前时刻；当前生命与持物以本页上方为准，实际后果以正文和已保存经历为准。</p><DossierFields reading :names="recordNames" :value="data.character.dossier" />
    </section>
    <section v-else-if="tab==='intentions'" aria-label="已采用的角色意愿">
     <p class="observer-note">最近12段保留故事中，{{data.character.name}}交给主API的意愿。它们并非已经完成的行动；没有明确说谎标记时不替人物判定。</p><p v-if="!selectedDecisions.length">此角色暂无已发布故事的意愿记录。</p>
     <article v-for="(decision,index) in selectedDecisions" :key="`${decision.logId}:${decision.frame}:${index}`" class="observer-decision" :class="{'observer-lie':decision.isLying===true}"><header><strong>{{decision.godName}}</strong><span>{{decisionSource(decision)}}<template v-if="decision.frame"> · 片段 {{decision.frame}}</template></span><mark v-if="decision.isLying===true">已记录说谎</mark><small v-else>{{decision.isLying===false?'未标记说谎':'说谎状态未记录'}}</small></header><div v-if="decision.innerThought" class="observer-intent-field"><b>真实想法：</b><DossierFields reading :names="recordNames" :value="decision.innerThought" /></div><div v-for="field in intentFields.filter(item=>decision[item.key])" :key="field.key" class="observer-intent-field"><b>{{field.label}}：</b><DossierFields reading :names="recordNames" :value="decision[field.key]" /></div><details v-if="decision.itemIntentions?.length || decision.combatDisposition"><summary>物品与交手处置意愿</summary><DossierFields reading :names="recordNames" :value="{itemIntentions:decision.itemIntentions,combatDisposition:decision.combatDisposition}" /></details><details v-if="decision.conditionalContests?.length"><summary>本段条件性公式参考与实际发生记录</summary><DossierFields reading :names="recordNames" :value="{conditionalContests:decision.conditionalContests,contestResolutions:decision.contestResolutions}" /></details></article>
    </section>
    <section v-else-if="tab==='memory'" aria-label="角色独立记忆">
     <p class="observer-note">{{data.character.name}}自己的设定、亲历与想法，最近保存的内容在前。位置、持物等资料单列为状态留痕。</p><nav class="memory-categories" aria-label="个人记录分类"><button type="button" :aria-pressed="memoryCategory==='story'" @click="memoryCategory='story'">经历与心迹 <small>{{memoryStories.length}}</small></button><button type="button" :aria-pressed="memoryCategory==='state'" @click="memoryCategory='state'">状态与物品 <small>{{memoryStates.length}}</small></button></nav><p v-if="!visibleMemories.length" class="observer-note">本页尚无{{memoryCategory==='story'?'经历与心迹':'状态与物品'}}记录，可继续读取更早内容。</p><CharacterMemoryEntry v-for="entry in visibleMemories" :key="entry.id" :entry="entry" :names="recordNames" :current-seconds="data.storySeconds"/><button v-if="data.hasMoreMemories" :disabled="loading" @click="load(true)">读取更早的记忆</button>
     
    </section>
    <section v-else aria-label="实际保存的裁决记录"><p class="observer-note">这里整理已经保存的结果。条件性公式参考不代表交手已经发生；没有记录时不补造分数。</p><details v-for="entry in data.ruleSnapshots" :key="entry.logId"><summary>第 {{entry.round}} 回合 · 故事记录 {{entry.logId}}</summary><DossierFields reading :names="recordNames" :value="entry.snapshot" /></details></section>
   </template>
   </div></div>
  </template>
  <StatFormulaDialog v-model="formulaVisible" :book="data?.character.formula" :initial-key="formulaKey" />
 </section>
</template>
<script setup>
import {ref,computed,watch,onBeforeUnmount} from 'vue'
import http from '../api'
import {storyMoment} from '../utils/storyTime'
import DossierFields from './DossierFields.vue'
import CharacterMemoryEntry from './CharacterMemoryEntry.vue'
import {isMemoryState} from '../utils/characterMemory'
import {dossierNamesFrom} from '../utils/dossierPresentation'
import StatFormulaDialog from './StatFormulaDialog.vue'
import {statDefinitions} from '../utils/sceneGuidance'
import {characterPortrait} from '../utils/characterPortrait'
import TheatreGlyph from './TheatreGlyph.vue'
const props=defineProps({sessionId:String,playerId:String,viewMode:String,refreshKey:String,branchEpoch:Number})
const data=ref(null),loading=ref(false),error=ref(''),selected=ref(''),tab=ref('character'),memories=ref([]),formulaVisible=ref(false),formulaKey=ref('zhi')
const tabs=[{key:'character',label:'真实资料'},{key:'intentions',label:'意愿与隐情'},{key:'memory',label:'独立记忆'},{key:'rules',label:'裁决记录'}]
const intentFields=[{key:'intention',label:'本段目标'},{key:'actionDirection',label:'行动方向'},{key:'communicationIntent',label:'交流意愿'},{key:'boundaries',label:'边界与条件'},{key:'deceptionIntent',label:'表达与隐瞒意图'},{key:'speech',label:'已记录对白'}]
const selectedDecisions=computed(()=>data.value?.decisions.filter(d=>d.godId===data.value.character.id) || [])
const recordNames=computed(()=>dossierNamesFrom(data.value))
const memoryCategory=ref('story')
const memoryStories=computed(()=>memories.value.filter(entry=>entry.type!=='ROLE_INTEGRATED' && !isMemoryState(entry)))
const memoryStates=computed(()=>memories.value.filter(isMemoryState))
const visibleMemories=computed(()=>memoryCategory.value==='story'?memoryStories.value:memoryStates.value)
function decisionSource(decision){const round=data.value?.ruleSnapshots?.find(entry=>entry.logId===decision.logId)?.round;return round?`第 ${round} 回合`:'已保存故事'}
const portrait=computed(()=>characterPortrait(data.value?.character || {},props.viewMode))
function chooseRole(id){if(loading.value || id===data.value?.character.id)return;selected.value=id;resetMemory()}
let sequence=0,alive=true
const identity=()=>[props.sessionId,props.playerId,props.viewMode,props.refreshKey,selected.value].join('|')
async function load(more=false){
 const request=++sequence,who=identity();if(props.viewMode!=='OMNISCIENT' || !props.sessionId){data.value=null;memories.value=[];return}
 loading.value=true;error.value='';if(!more){data.value=null;memories.value=[]}
 try{const result=await http.get('/game/perspective',{params:{sessionId:props.sessionId,roleId:selected.value || undefined,beforeMemory:more?data.value?.nextBefore:undefined}})
  if(!alive || request!==sequence || who!==identity())return
  if(result.sessionId!==props.sessionId || result.viewMode!=='OMNISCIENT' || !result.available || props.branchEpoch!=null && result.branchEpoch!==props.branchEpoch){error.value='视角或存档正在同步，请刷新资料。';return}
  data.value=result;selected.value=result.character.id;memories.value=more?[...memories.value,...result.memories]:result.memories
 }catch{if(alive && request===sequence && who===identity())error.value='暂时未能读取天眼资料，请重试。'}finally{if(alive && request===sequence)loading.value=false}
}
function resetMemory(){memories.value=[];load()}
watch(()=>[props.sessionId,props.playerId,props.viewMode],()=>{selected.value='';tab.value='character';memoryCategory.value='story';data.value=null;memories.value=[];formulaVisible.value=false},{flush:'sync'})
watch(()=>[props.sessionId,props.playerId,props.viewMode,props.refreshKey],()=>load(),{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;sequence++;data.value=null;memories.value=[]})
</script>
<style scoped>
.observer-intent-field{margin:.6rem 0}
.memory-categories{display:flex;flex-wrap:wrap;gap:8px;margin:17px 0 22px;padding-bottom:14px;border-bottom:1px solid #a5b7c943}.memory-categories button{font-size:12px;padding:8px 13px}.memory-categories button[aria-pressed=true]{box-shadow:inset 0 -2px #69869f;border-color:#7691a9}.memory-categories small{margin-left:8px;font:10px system-ui}
.perspective-panel{color:#ddd8c9;line-height:1.8}.perspective-panel>header>p:first-child{color:#d9bd7b;letter-spacing:.2em}.perspective-panel h2{font-size:27px;font-weight:500}.perspective-panel h3{font-size:22px}.perspective-panel small,.observer-note{color:#a6b2bd;font-size:12px}.observer-controls,.observer-tabs,.observer-decision header,.observer-memory header{display:flex;align-items:center;flex-wrap:wrap;gap:12px;margin:18px 0}.observer-controls select,.perspective-panel button{background:#1c2e40;color:#e5dcc7;border:1px solid #53616d;border-radius:5px;padding:8px 13px}.observer-tabs button[aria-pressed=true]{border-color:#dbbe7c;color:#e5c884}.perspective-panel button:disabled{opacity:.5}.observer-stats{display:grid;grid-template-columns:repeat(6,1fr);gap:8px}.observer-stats button{display:flex;flex-direction:column}.observer-stats strong{font-size:21px}.perspective-panel details,.observer-decision,.observer-memory{border:1px solid #3c5062;border-radius:6px;padding:14px;margin:14px 0;overflow-wrap:anywhere}.perspective-panel summary{cursor:pointer;color:#d6c290}.observer-decision header span,.observer-memory header span{font-size:12px;color:#9fadb9}.observer-lie{border-color:#aa6855;background:#5c2f2929}.observer-lie mark{background:#713e37;color:#ffdab6;padding:1px 7px;border-radius:3px}.observer-prose{white-space:pre-wrap}.perspective-panel [role=alert]{color:#e6a590}@media(max-width:600px){.observer-stats{grid-template-columns:repeat(3,1fr)}}
</style>
