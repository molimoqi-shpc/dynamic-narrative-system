<template>
  <button v-if="session.playerId && route.path!=='/login'" class="character-orbit-button" type="button" aria-label="查看自己的人物面板" @click="openPanel"><span aria-hidden="true">✦</span> 命谱 <kbd>P</kbd></button>
  <el-drawer v-model="visible" title="命谱 · 此身" direction="ltr" size="min(1040px, 96vw)" class="character-drawer illuminated-profile" :class="`profile-section-${tab}`" destroy-on-close @closed="data=null">
    <div class="profile-sheet">
      <FlightMark class="profile-flight" />
      <div class="profile-toolbar"><p>此身有归，万象有忆。</p><div><el-select v-if="saves.length>1" v-model="selectedSid" aria-label="查看人物所属存档" @change="loadProfile" size="small"><el-option v-for="(save,index) in saves" :key="save.sessionId" :value="save.sessionId" :label="`第 ${index+1} 卷 · ${save.sessionId===session.sessionId?'当前旅程':statusText(save.status)}`" /></el-select><el-button text :loading="loading" @click="refresh">刷新档案</el-button></div></div>
      <div v-if="loading" class="profile-loading" role="status"><span class="loading-orbit" />正在召回此身的记忆…</div>
      <div v-else-if="error" class="profile-empty" role="alert">{{error}}<el-button @click="refresh">重试</el-button></div>
      <div v-else-if="!data" class="profile-empty">尚无已诞生的人物。先开启一段旅程，或恢复已有存档。</div>
      <template v-else>
        <header class="profile-identity"><div class="profile-hero-art" aria-hidden="true"/><div class="profile-sigil" aria-hidden="true"><FolioSeal kind="identity"/><span>{{data.name?.slice(0,1) || '命'}}</span></div><div><p class="profile-eyebrow">此生 · {{statusText(data.status)}}</p><h2 class="divine-title">{{data.name}}</h2><p>{{data.location}} <span>·</span> 第 {{data.round}} 回合 <span>·</span> {{data.emotion}}</p><div class="profile-personality"><span v-for="tag in data.personality || []" :key="tag">{{tag}}</span></div></div><div class="profile-hero-facts"><span>此身生命 <strong>{{data.health ?? 100}}</strong><small>/ 100</small></span><span>珍藏 <strong>{{meaningfulItems.length}}</strong><small>件</small></span></div></header>
        <div class="profile-layout"><nav class="profile-tabs selection-track" aria-label="人物面板内容"><SelectionRibbon :value="tab"/><button v-for="item in tabs" :key="item.key" type="button" :aria-pressed="tab===item.key" @click="tab=item.key">{{item.label}}</button></nav><div v-folio-motion="tab" class="profile-content">
        <section v-if="tab==='self'" class="profile-overview">
          <div class="profile-card"><div class="profile-section-heading"><h3>身体生命</h3><strong>{{data.health ?? 100}} / 100</strong></div><progress :value="data.health ?? 100" max="100" aria-label="当前生命" style="width:100%" /><details class="profile-health-note"><summary>伤势与际遇</summary><p class="profile-note">生命随实际伤势与治疗变化。切磋可以点到为止；战败之后，留情、被俘、救援或死亡，都有各自的缘由与后果。</p></details></div>
          <div class="profile-card"><div class="profile-section-heading"><h3>六维相生</h3><small>基础 / 实际 · 点击查看公式</small></div><div class="profile-stat-composition"><SixfoldChart :stats="data.actualStats"/><div class="profile-stat-grid"><button v-for="stat in statDefinitions" :key="stat.key" type="button" class="stat-aura" :style="{'--stat-delay':`${statDefinitions.indexOf(stat)*-.8}s`}" @click="showFormula(stat.key)" :aria-label="`查看${stat.label}的六维公式`"><span>{{stat.label}}</span><strong>{{data.actualStats[stat.key]}}</strong><small>基础 {{data.baseStats[stat.key]}}</small><i aria-hidden="true">↗</i></button></div></div><p class="profile-note">{{data.formula?.version}} · 实际值含根基修正与有限协同。</p></div>
          <div class="profile-card profile-divinity"><span class="divinity-star" aria-hidden="true">✧</span><p class="profile-eyebrow">身份与根基</p><h3 class="divine-title">{{data.divinity}}</h3><p>能力根基 · 本 {{data.baseStats.ben}}</p><p>忠诚意志 · 归 {{data.baseStats.gui}}</p><small>身份以当前角色记录为准，根基数值不自动授予额外身份或能力。</small></div>
          <div v-if="data.talents?.length" class="profile-card profile-wide"><h3>天赋所承</h3><div v-for="talent in data.talents" :key="talent.name" class="profile-talent"><strong>{{talent.name}}</strong><p v-if="talent.effectKind==='LEGACY' || (!talent.effectKind && !talent.description?.startsWith('【开局增益：'))" class="profile-note">旧版叙事天赋记录：描述中的特殊效果尚未绑定本版数值结算。</p><p>{{visibleText(talent.description)}}</p></div></div>
          <details v-if="data.birthStory" class="profile-card profile-wide"><summary>来时之路 · 身世</summary><p class="profile-story">{{data.birthStory}}</p></details>
        </section>
        <section v-else-if="tab==='items'" class="important-inventory"><header class="collection-heading"><p>得之有因 · 藏之有忆</p><div><h3>行囊珍藏</h3><span>{{meaningfulItems.length}} 件</span></div><p class="profile-note">记下真正获得的装备、奇物与故事信物。寻常用品随叙事使用，不铺满行囊。</p></header>
         <div class="collection-tools"><label><span class="sr-only">搜索珍藏</span><input v-model="itemSearch" aria-label="搜索珍藏" placeholder="寻找名称、用途或一段来历…" /></label><select v-model="itemSort" aria-label="珍藏排序"><option value="STORY">按类别</option><option value="NAME">按名称</option></select></div>
         <nav class="collection-filters" aria-label="珍藏分类"><button v-for="category in itemCategories" :key="category.key" :aria-pressed="itemCategory===category.key" @click="itemCategory=category.key">{{category.label}}</button></nav>
         <div v-if="!meaningfulItems.length" class="collection-empty"><RelicGlyph /><h4>行囊尚待新的故事</h4><p>还没有记下重要珍藏。真正获得的神器、装备与剧情信物，将连同来历和已知用途留在这里。</p></div>
         <p v-else-if="!filteredItems.length" class="profile-note">未找到符合条件的珍藏，试试其他名称或分类。</p>
         <div v-else class="collection-layout"><div class="relic-list" aria-label="重要物品列表"><button v-for="item in filteredItems" :key="item.ref" class="relic-tile" :class="`relic-${item.kind || 'OTHER'}`" :aria-pressed="selectedInventory?.ref===item.ref" @click="selectedItem=item.ref"><RelicGlyph :kind="item.kind"/><span><small>{{kindLabel(item.kind)}}</small><strong>{{item.name}}</strong><em v-if="item.isBorrowed">借持</em></span></button></div>
          <article v-if="selectedInventory" v-folio-motion="selectedInventory.ref" v-paper-light class="relic-detail tactile-surface"><PaperFinish/><header><div class="relic-showcase" aria-hidden="true"><RelicGlyph :kind="selectedInventory.kind"/></div><RelicGlyph :kind="selectedInventory.kind"/><p>{{kindLabel(selectedInventory.kind)}}<span v-if="selectedInventory.isBorrowed"> · 借自{{selectedInventory.ownerName}}</span></p><h4>{{selectedInventory.name}}</h4><p v-if="selectedInventory.appearance" class="relic-appearance">{{selectedInventory.appearance}}</p></header><p v-if="selectedInventory.description" class="relic-description">{{selectedInventory.description}}</p><section v-for="field in itemDetailFields" v-show="selectedInventory[field.key] || (field.key==='origin' && selectedInventory.source)" :key="field.key"><h5>{{field.label}}</h5><p>{{selectedInventory[field.key] || (field.key==='origin'?selectedInventory.source:'')}}</p></section><p v-if="!selectedInventory.knownEffects" class="profile-note">尚未显露的作用，仍待亲自了解。</p><button v-if="selectedSid===session.sessionId && route.path==='/play'" class="folio-consult-link" @click="consultBook({keyword:selectedInventory.name})">在世诰中查阅这件珍藏 ↗</button></article>
         </div>
        </section>
        <section v-else class="profile-relationships"><header class="collection-heading"><p>相逢有迹 · 恩怨有由</p><h3>此生牵系</h3><p class="profile-note">{{data.relationshipBasis}}</p></header><div class="relation-totals"><span v-for="group in relationGroups" :key="group.key">{{group.label}} <strong>{{data[group.key]?.length || 0}}</strong></span></div><div v-if="!visibleRelationGroups.length" class="relation-empty"><FolioSeal kind="identity"/><h4>相逢，尚待后来</h4><p>真实的交往，会在这里留下名字与来由。</p></div><div v-for="group in visibleRelationGroups" :key="group.key" class="profile-card relation-chapter"><div class="profile-section-heading"><h3>{{group.label}}</h3><small>{{data[group.key]?.length || 0}} 位</small></div><div v-if="data[group.key]?.length" class="journal-relations"><article v-for="person in data[group.key]" :key="person.godId" class="journal-relation"><span class="relation-emblem" aria-hidden="true">{{person.name.slice(0,1)}}</span><div><header><h4>{{person.name}}</h4><span>{{person.label}}</span></header><p v-if="person.summary">{{person.summary}}</p><p class="relation-basis">{{person.basis}}<template v-if="person.lastSeenSeconds!=null"> · {{storyMoment(person.lastSeenSeconds)}}所见</template></p><button v-if="selectedSid===session.sessionId && route.path==='/play'" class="folio-consult-link" @click="consultBook({roleId:person.godId})">查阅此人见闻 ↗</button><details v-if="person.lastInteraction"><summary>最近交往</summary><p>{{person.lastInteraction}}</p></details></div></article></div><p v-else class="profile-note">{{group.empty}}</p></div></section></div></div>
      </template>
    </div>
  </el-drawer>
  <StatFormulaDialog v-model="formulaVisible" :book="data?.formula" :initial-key="formulaKey" />
</template>
<script setup>
import PaperFinish from '../components/PaperFinish.vue'
import {paperLight as vPaperLight} from '../utils/paperLight'
import SelectionRibbon from './SelectionRibbon.vue'
import {characterPanelPresentation} from '../utils/panelPresentation'
import {folioMotion as vFolioMotion} from '../utils/folioMotion'
import {storyMoment} from '../utils/storyTime'
import {visibleText} from '../utils/visibleText'
import {ref,computed,onMounted,onBeforeUnmount,watch} from 'vue'
import {useRoute} from 'vue-router'
import {ElDrawer,ElButton,ElSelect,ElOption} from 'element-plus'
import {useSessionStore} from '../store/session'
import {statDefinitions} from '../utils/sceneGuidance'
import StatFormulaDialog from './StatFormulaDialog.vue'
import RelicGlyph from './RelicGlyph.vue'
import FlightMark from './FlightMark.vue'
import FolioSeal from './FolioSeal.vue'
import SixfoldChart from './SixfoldChart.vue'
import http from '../api'
const route=useRoute(),session=useSessionStore()
const visible=ref(false),loading=ref(false),error=ref(''),data=ref(null),saves=ref([]),selectedSid=ref(''),tab=ref('self')
const formulaVisible=ref(false),formulaKey=ref('zhi');let requestNo=0
const selectedItem=ref('')
const itemSearch=ref(''),itemCategory=ref('ALL'),itemSort=ref('STORY')
const itemCategories=[{key:'ALL',label:'全部'},{key:'ARTIFACT',label:'神器奇物'},{key:'EQUIPMENT',label:'装备'},{key:'KEY_ITEM',label:'剧情信物'},{key:'CONSUMABLE',label:'药物与消耗'},{key:'KEEPSAKE',label:'纪念珍藏'}]
const kindLabel=kind=>({ARTIFACT:'神器奇物',EQUIPMENT:'装备',KEY_ITEM:'剧情信物',CONSUMABLE:'重要消耗品',KEEPSAKE:'纪念珍藏',OTHER:'故事珍藏'}[kind] || '故事珍藏')
const itemDetailFields=[{key:'knownEffects',label:'已知用途'},{key:'significance',label:'故事牵系'},{key:'limitations',label:'已知限制'},{key:'origin',label:'得来之路'}]
const meaningfulItems=computed(()=>data.value?.importantItems || [])
const filteredItems=computed(()=>{
 const query=itemSearch.value.trim().toLocaleLowerCase();const list=meaningfulItems.value.filter(item=>(itemCategory.value==='ALL' || item.kind===itemCategory.value) && (!query || [item.name,item.description,item.knownEffects,item.significance,item.origin,item.source].some(value=>String(value || '').toLocaleLowerCase().includes(query))))
 return [...list].sort((a,b)=>itemSort.value==='NAME'?a.name.localeCompare(b.name,'zh-CN'):(itemCategories.findIndex(c=>c.key===a.kind)+10)%10-(itemCategories.findIndex(c=>c.key===b.kind)+10)%10)
})
const selectedInventory=computed(()=>filteredItems.value.find(item=>item.ref===selectedItem.value) || filteredItems.value[0])
const tabs=[{key:'self',label:'此身与六维'},{key:'items',label:'行囊珍藏'},{key:'relations',label:'人物牵系'}]
const relationGroups=[{key:'friends',label:'朋友与盟友',empty:'还没有已建立的友谊或盟约。'},{key:'enemies',label:'敌对与戒备',empty:'尚未留下明确敌对或戒备的交往记录。'},{key:'acquaintances',label:'亲缘与相识',empty:'尚无已记录的相识；实际见面与交往后会留在这里。'}]
const visibleRelationGroups=computed(()=>relationGroups.filter(group=>(data.value?.[group.key]?.length || 0)>0))
const statusText=status=>({PLAYING:'旅程之中',DEAD:'此生已尽',RETIRED:'此生归隐',VICTORY:'天命已成',SUMMARIZED:'旧梦留存'}[status] || '命途未启')
function consultBook(query){if(selectedSid.value!==session.sessionId || route.path!=='/play')return;visible.value=false;window.dispatchEvent(new CustomEvent('open-world-book',{detail:{sessionId:selectedSid.value,...query}}))}
function showFormula(key){formulaKey.value=key;formulaVisible.value=true}
function closeRequested(){visible.value=false}
async function openPanel(){if(!session.playerId)return;visible.value=true;tab.value='self';await refresh()}
async function openRequested(event){if(!session.playerId)return;visible.value=true;tab.value=['self','items','relations'].includes(event.detail?.tab)?event.detail.tab:'self';await refresh()}
async function refresh(){
  const request=++requestNo;loading.value=true;error.value='';data.value=null
  try{const response=await http.get('/game/sessions');if(request!==requestNo || !visible.value)return
    saves.value=(response.sessions || []).filter(s=>['PLAYING','DEAD','RETIRED','VICTORY','SUMMARIZED'].includes(s.status))
    selectedSid.value=saves.value.some(s=>s.sessionId===session.sessionId)?session.sessionId:saves.value.some(s=>s.sessionId===selectedSid.value)?selectedSid.value:saves.value[0]?.sessionId || ''
    if(selectedSid.value){const profile=await http.get('/game/character',{params:{sessionId:selectedSid.value}});if(request===requestNo && visible.value)data.value=profile}
  }catch(e){if(request===requestNo)error.value=e.response?.data?.message || '人物档案暂时无法读取，请重试。'}finally{if(request===requestNo)loading.value=false}
}
async function loadProfile(){const request=++requestNo;loading.value=true;data.value=null;error.value='';try{const profile=await http.get('/game/character',{params:{sessionId:selectedSid.value}});if(request===requestNo && visible.value)data.value=profile}catch(e){if(request===requestNo)error.value=e.response?.data?.message || '人物读取失败。'}finally{if(request===requestNo)loading.value=false}}
function shortcut(e){if(e.key.toLowerCase()!=='p' || e.ctrlKey || e.metaKey || e.altKey || e.repeat || e.target.closest?.('input,textarea,select,[contenteditable=true],[role=dialog]'))return;if(session.playerId && route.path!=='/login'){e.preventDefault();openPanel()}}
watch(visible,open=>{if(!open){requestNo++;loading.value=false;formulaVisible.value=false}})
watch(()=>[visible.value,tab.value],([open,section])=>Object.assign(characterPanelPresentation,{open,tab:section}),{flush:'sync',immediate:true})
watch(()=>[session.playerId,session.sessionGeneration,session.branchEpoch],()=>{requestNo++;visible.value=false;formulaVisible.value=false;data.value=null;loading.value=false},{flush:'sync'})
function refreshIfVisible(){if(visible.value && selectedSid.value===session.sessionId)loadProfile()}
onMounted(()=>{window.addEventListener('keydown',shortcut);window.addEventListener('character-state-changed',refreshIfVisible);window.addEventListener('open-character-panel',openRequested);window.addEventListener('close-character-panel',closeRequested)})
onBeforeUnmount(()=>{Object.assign(characterPanelPresentation,{open:false,tab:'self'});window.removeEventListener('close-character-panel',closeRequested);requestNo++;window.removeEventListener('keydown',shortcut);window.removeEventListener('character-state-changed',refreshIfVisible);window.removeEventListener('open-character-panel',openRequested)})
</script>
<style>
/* The drawer is teleported to body and must remain readable on home and play alike. */
.character-drawer.el-drawer{background:#142238;color:#e1e8f2;--myth-ink:#e1e8f2;--myth-ink-dim:#b7c6d9;--myth-bronze:#dfcba6;--myth-gold:#d2bc92;--myth-bg-alt:#1a2c44;--myth-bg-card:#1b2d46;--myth-divider:#bacbdf2e;--el-text-color-primary:#e1e8f2;--el-text-color-regular:#c2d0e2;--el-color-primary:#dfcba6}
.character-drawer.el-drawer .el-drawer__header{padding:20px 24px 16px;color:#dfcba6}
.character-drawer.el-drawer .el-drawer__title{color:#dfcba6}
.character-drawer.el-drawer .el-drawer__body{padding:0 24px 24px}
.character-drawer.el-drawer .profile-toolbar{padding:10px 0;font-size:12px;color:#b7c6d9}
.character-drawer.el-drawer .profile-toolbar p{margin:0;color:#b7c6d9}
.character-drawer.el-drawer .profile-identity{padding:16px 0;gap:18px}
.character-drawer.el-drawer .profile-identity h2{color:#ead6ae;margin:4px 0 8px}
.character-drawer.el-drawer .profile-identity p{color:#b7c6d9;font-size:12px;margin:4px 0}
.character-drawer.el-drawer .profile-sigil{width:60px;height:60px;background:#b7c9e318;box-shadow:none;border-color:#cbb78d66}
.character-drawer.el-drawer .profile-sigil span{font-size:25px;color:#dfcba6}
.character-drawer.el-drawer .profile-tabs{padding-bottom:8px;margin-bottom:16px;border-color:#bacadd38}
.character-drawer.el-drawer .profile-tabs button{color:#b7c6d9;font-size:13px;padding:9px 8px}
.character-drawer.el-drawer .profile-tabs button[aria-pressed=true]{color:#ead6ae;background:#c3ab7b16;border-bottom:1px solid #c5ad79}
.character-drawer.el-drawer .profile-cloud{display:none}
.character-drawer.el-drawer .profile-note,.character-drawer.el-drawer .profile-card>p,.character-drawer.el-drawer .profile-divinity small{color:#b7c6d9;font-size:12px}
.character-drawer.el-drawer .profile-card h3{color:#dfcba6}
.character-drawer.el-drawer .profile-stat-grid button{color:#e1e8f2}
.character-drawer.el-drawer .collection-heading p{margin:6px 0}
.character-drawer.el-drawer .collection-heading{padding-bottom:10px}
.character-drawer.el-drawer .collection-tools{margin-top:14px}
.character-drawer.el-drawer .collection-filters{margin:12px 0 16px}
.character-drawer.el-drawer .collection-filters button,.character-drawer.el-drawer .relic-detail h5{font-size:12px}
.character-drawer.el-drawer .relic-detail section>p,.character-drawer.el-drawer .journal-relation p{font-size:13px}
.character-drawer.el-drawer .relic-detail header>.relic-glyph{width:72px;height:72px}
.character-drawer.el-drawer .collection-tools select option{background:#142238;color:#e1e8f2}
</style>
<style scoped>
.profile-sheet .profile-identity{padding:25px 0;gap:20px}.profile-sheet .profile-identity h2{font-size:30px}.profile-sheet .profile-overview{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.profile-sheet .profile-overview>.profile-card{min-width:0;margin:0}.profile-sheet .profile-overview>.profile-wide{grid-column:1/-1}.profile-sheet .profile-overview>.profile-card:first-child{grid-column:1/-1}.profile-sheet .profile-tabs{display:flex;gap:20px;margin-bottom:20px}.profile-sheet .profile-tabs button{font-size:13px;padding:12px 0}.collection-heading{padding:5px 0 16px;border-bottom:1px solid #c5b38230}.collection-heading>p:first-child{font-size:9px;letter-spacing:3px;color:#a9b7c5}.collection-heading>div{display:flex;align-items:center;justify-content:space-between}.collection-heading h3{font:24px var(--myth-font-title,serif);color:#e1cda7;margin:8px 0 10px}.collection-heading span{font-size:12px;color:#a6b3c1}.collection-tools{display:flex;gap:10px;margin-top:20px}.collection-tools label{flex:1;min-width:0}.collection-tools input,.collection-tools select{width:100%;box-sizing:border-box;background:#09162660;border:1px solid #b9c5d533;border-radius:4px;color:#dce2e9;padding:10px 12px;font:12px system-ui}.collection-tools select{width:100px}.collection-filters{display:flex;gap:6px;flex-wrap:wrap;margin:14px 0 20px}.collection-filters button{background:transparent;border:1px solid #c6b28324;border-radius:3px;padding:6px 10px;color:#a8b9ca;font:10px system-ui;cursor:pointer}.collection-filters button[aria-pressed=true]{border-color:#c9b18199;background:#b7a16e15;color:#e1cda7}.collection-layout{display:grid;grid-template-columns:minmax(180px,.85fr) minmax(0,1.3fr);gap:20px;align-items:start}.relic-list{display:grid;gap:8px}.relic-tile{display:flex;align-items:center;gap:8px;width:100%;padding:12px 10px;border:1px solid #a8bbd42b;border-radius:4px 12px 4px 4px;background:linear-gradient(135deg,#cfba810c,#8fadc505);color:#bdc9d7;text-align:left;cursor:pointer;transition:border-color .2s,background .2s}.relic-tile[aria-pressed=true]{border-color:#d5b779ae;background:linear-gradient(135deg,#d7bb7420,#b7a16e08);color:#e2cb95;box-shadow:inset 2px 0 #d1b579}.relic-tile>.relic-glyph{flex:none;width:55px;height:55px}.relic-tile span{display:flex;flex-direction:column;gap:6px;min-width:0}.relic-tile small{font-size:8px;letter-spacing:1px;opacity:.7}.relic-tile strong{font:15px/1.5 var(--myth-font-title,serif);overflow-wrap:anywhere}.relic-tile em{font-size:8px;font-style:normal;color:#a6b8ce}.relic-detail{position:sticky;top:0;min-width:0;padding:22px;border:1px solid #cbb67e40;border-radius:4px 20px 4px 4px;background:radial-gradient(ellipse at 50% 0,#d1b57412,transparent 55%),linear-gradient(145deg,#8facc60c,#12223a20)}.relic-detail header{text-align:center;border-bottom:1px solid #c9b38230;padding-bottom:16px}.relic-detail header>.relic-glyph{width:100px;height:100px;color:#d8bc7f}.relic-detail header>p:first-of-type{font-size:10px;letter-spacing:2px;color:#acb9c9}.relic-detail h4{font:26px/1.5 var(--myth-font-title,serif);color:#e6d4ad;margin:8px 0 12px}.relic-detail .relic-appearance{font:italic 12px/1.9 var(--myth-font-body,serif);color:#a6b6c9}.relic-detail section{margin-top:20px}.relic-detail h5{font-size:10px;letter-spacing:2px;font-weight:400;color:#c8b58e;margin:0 0 7px}.relic-detail section>p,.relic-description{font-size:12px;line-height:2;color:#c1cedd;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}.relic-description{margin-top:16px}.collection-empty{text-align:center;padding:35px 20px;color:#a0b2c7;border:1px dashed #b4c4d42b;border-radius:4px}.collection-empty>.relic-glyph{color:#c6b07a;opacity:.6}.collection-empty h4{font:21px var(--myth-font-title,serif);color:#d9c39c}.collection-empty p{font-size:12px;line-height:2;max-width:360px;margin:0 auto}.relation-totals{display:flex;gap:20px;padding:18px 0;color:#aebed0;font-size:11px;flex-wrap:wrap}.relation-totals strong{font:20px var(--myth-font-title,serif);color:#d6c49f;margin-left:6px}.journal-relation{display:flex;gap:14px;padding:17px 0;border-top:1px solid #c2ccd51c}.journal-relation>div{flex:1;min-width:0}.journal-relation .relation-emblem{display:flex;align-items:center;justify-content:center;flex:none;width:42px;height:46px;border:1px solid #d0bb873b;border-radius:3px 10px;background:linear-gradient(140deg,#c6af7020,transparent);font:21px var(--myth-font-title,serif);color:#d2bd91}.journal-relation header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.journal-relation h4{margin:0;font:17px var(--myth-font-title,serif);color:#ddd0b4}.journal-relation header>span{font-size:10px;color:#c7b180}.journal-relation p{font-size:12px;line-height:1.9;color:#bdcadd;white-space:pre-wrap;overflow-wrap:anywhere}.journal-relation .relation-basis{font-size:10px;color:#91a7c1}.journal-relation summary{font-size:10px;color:#c8b78e;cursor:pointer}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}@media(max-width:560px){.profile-sheet .profile-overview{grid-template-columns:1fr}.profile-sheet .profile-overview>.profile-card{grid-column:auto}.collection-layout{grid-template-columns:1fr}.relic-list{grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.relic-tile{padding:9px 6px;gap:3px}.relic-tile>.relic-glyph{width:40px;height:40px}.relic-tile strong{font-size:12px}.relic-detail{position:static;padding:18px}.profile-sheet .profile-identity h2{font-size:26px}.profile-sheet .profile-tabs{gap:18px}.profile-sheet .profile-tabs button{font-size:12px}}
</style>
