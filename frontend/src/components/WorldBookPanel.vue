<template>
 <section class="world-book-panel illuminated-book" aria-label="世诰查询">
  <header class="book-heading"><div><p>万象留痕 / 此生之书</p><h2>世诰<span aria-hidden="true">诰</span></h2><span>{{viewMode==='OMNISCIENT'?'天眼 · 幕后档案可阅':'入梦 · 仅限你的已知资料'}} · {{storyMoment(data?.storySeconds ?? storySeconds,{currentSeconds:storySeconds,currentLabel:storyTime})}}</span></div><FolioSeal kind="world"/></header>
  <div class="shigao-spread"><aside class="book-directory"><div class="directory-art" aria-hidden="true"/><p>此卷目录</p><nav class="book-actions selection-track" aria-label="世诰类别"><SelectionRibbon :value="bookSection"/><button type="button" :aria-pressed="bookSection==='world'" @click="bookSection='world'"><small>I</small>天地卷首<span>↗</span></button><button type="button" :aria-pressed="bookSection==='records'" @click="showPlots=false"><small>II</small>人物与见闻<span>{{data?.total ?? '—'}}</span></button><button type="button" :aria-pressed="showPlots" @click="showPlots=true"><small>III</small>剧情与线索<span>↗</span></button></nav><div class="directory-note"><span>{{viewMode==='OMNISCIENT'?'天眼所观':'入梦所知'}}</span><p>{{viewMode==='OMNISCIENT'?'意愿尚非结果。已发生的事，另有留痕。':'只记亲历与已知。尚未揭晓的，仍在故事深处。'}}</p></div></aside>
   <div v-folio-motion="bookSection" class="book-body">
    <section v-if="bookSection==='world'" class="book-world-leaf"><p class="edition-eyebrow">卷首 / 此生所在</p><h3>{{worldSetting?.title || '此间天地'}}</h3><p class="book-world-genre">{{[worldSetting?.genre,worldSetting?.era].filter(Boolean).join(' · ')}}</p><div v-if="worldSetting?.summary || worldSetting?.introduction" class="book-world-prose">{{worldSetting.summary || worldSetting.introduction}}</div><p v-else class="book-note">此卷尚未留存独立的世界介绍，可从人物与见闻中翻阅已知故事。</p><blockquote>来路各异，相逢有时。<br/>这一卷，记下你的所见与后来。</blockquote><button type="button" class="book-world-continue" @click="bookSection='records'">翻阅人物与见闻 ↗</button></section>
    <PlotBookPanel v-else-if="showPlots" :session-id="sessionId" :player-id="playerId" :view-mode="viewMode" :refresh-key="refreshKey"/>
    <template v-else>
     <header class="book-section-title"><div><p class="edition-eyebrow">人物与见闻 / II</p><h3>相逢与留痕</h3></div><span>{{viewMode==='OMNISCIENT'?'此刻档案':'已知记录'}}</span></header>
     <p class="book-note book-perspective-note">{{viewMode==='OMNISCIENT'?'附加表中的想法与意愿不等于已经发生的行动。':'人物卡只记录你的所见，之后未感知的变化不会自动更新；性格需从实际言行中了解。'}}</p>
     <form class="book-search" @submit.prevent="search"><label>关键词<input v-model="filters.keyword" maxlength="120" placeholder="寻找故事、人物或地点…"/></label><label>角色<select v-model="filters.roleId"><option value="">全部已知角色</option><option v-for="role in data?.roles || []" :key="role.id" :value="role.id">{{role.name}}</option></select></label><details class="book-time-range"><summary>按世界时刻筛选 <span>＋</span></summary><div><label>世界时刻起（秒）<input v-model="filters.fromSeconds" type="number" min="0" step="1" placeholder="不限"/></label><label>世界时刻止（秒）<input v-model="filters.toSeconds" type="number" min="0" step="1" placeholder="不限"/></label></div></details><div class="book-actions book-search-actions"><button type="submit" :disabled="loading">查询 ↗</button><button type="button" @click="reset">清空</button><button type="button" :disabled="loading" @click="load">刷新</button></div></form>
     <p v-if="error" role="alert" class="book-error">{{error}}</p><div v-if="loading" role="status" class="book-loading"><FolioSeal/><p>正在翻阅世诰…</p></div>
     <template v-if="data"><section aria-label="当前世界时刻人物" class="book-people"><header class="book-subheading"><h3>{{viewMode==='OMNISCIENT'?'此刻活跃人物':'最近所见人物'}}</h3><span>{{data.activeCharacters?.length || 0}} 位</span></header><p v-if="!data.activeCharacters?.length" class="book-note book-empty">尚无可以指认的人物。相逢之后，见闻自会留在这里。</p><div class="book-cards"><article v-for="person in data.activeCharacters || []" :key="person.id" class="book-card" :class="{'book-card-extra':person.tier==='EXTRA'}"><div class="book-card-art" :class="{'book-art-unknown':!portrait(person).src}" aria-hidden="true"><img v-if="portrait(person).src" class="book-card-portrait" :src="portrait(person).src" :style="portrait(person).framing?.card" alt="" loading="lazy"/><span v-else>{{person.name?.slice(0,1) || '未'}}</span></div><div class="book-card-copy"><header><strong>{{person.name}}</strong><span>{{person.tier==='CORE'?'主要角色':person.tier==='EXTRA'?'普通人物':'已知人物'}}</span></header><p class="book-note">{{person.basis}}<template v-if="person.snapshotAtSeconds!=null"> · 档案：{{moment(person.snapshotAtSeconds)}}</template></p><div v-if="person.known" class="book-known"><p class="book-note">所见时刻：{{moment(person.known.observedAtSeconds,person.known.storyTime)}}<template v-if="person.known.awake!=null"> · {{person.known.awake?'清醒':'未清醒'}}</template><template v-if="person.known.bound"> · 受束缚</template></p><p v-if="person.known.position && person.known.position!=='原场景位置'">{{person.known.position}}</p><p v-if="person.known.lastObservedProcess || person.known.activity">{{person.known.lastObservedProcess || person.known.activity}}</p></div><DossierFields v-else-if="person.tier==='EXTRA'" :value="person.dossier?.brief"/><details v-else><summary>展开角色附加表</summary><p class="book-note">{{person.snapshotNote}}</p><DossierFields :names="recordNames" :value="person.dossier"/></details><button type="button" class="book-person-link" @click="focusRole(person.id)">查阅相关见闻 ↗</button></div></article></div></section>
      <section aria-label="查询记录" class="book-records"><header class="book-subheading"><h3>留存记录</h3><span>{{data.total}} 条</span></header><p v-if="data.timeNote" class="book-note">{{data.timeNote}}</p><p v-if="!data.entries?.length" class="book-note book-empty">没有符合条件的已知记录。可以换个关键词，或翻看全部见闻。</p><nav v-if="data.entries?.length>1" class="book-entry-index" aria-label="本页见闻目录"><button v-for="entry in data.entries" :key="entry.entry_key" :aria-pressed="readingEntry===entry.entry_key" @click="readEntry(entry.entry_key)">{{entryTitle(entry)}}</button></nav><article v-for="(entry,index) in data.entries || []" :key="entry.entry_key" class="book-entry" :class="{'entry-in-focus':readingEntry===entry.entry_key}" :data-entry-key="entry.entry_key" tabindex="-1"><span class="entry-leaf-number" aria-hidden="true">{{String(index+1).padStart(2,'0')}}</span><header><strong>{{entryTitle(entry)}}</strong><span>{{entry.source_label}}</span></header><p class="book-note">{{entryTime(entry)}}<template v-if="entry.location_name"> · {{entry.location_name}}</template></p><p v-if="typeof entry.content==='string' && !isStructuredDossier(entry.content)" class="book-prose">{{chapterProse(entry)}}</p><details v-else><summary>查看存档内容</summary><DossierFields :names="recordNames" :value="entry.content"/></details></article><nav class="book-pagination" aria-label="世诰分页"><button :disabled="loading || page===0" @click="goPage(page-1)">上一页</button><span>第 {{page+1}} 页</span><button :disabled="loading || !data.hasMore" @click="goPage(page+1)">下一页</button></nav></section>
     </template>
    </template>
   </div>
  </div>
 </section>
</template>
<script setup>
import SelectionRibbon from './SelectionRibbon.vue'
import {folioMotion as vFolioMotion} from '../utils/folioMotion'
import {ref,reactive,computed,watch,onBeforeUnmount} from 'vue'
import http from '../api'
import DossierFields from './DossierFields.vue'
import {dossierNamesFrom,isStructuredDossier} from '../utils/dossierPresentation'
import PlotBookPanel from './PlotBookPanel.vue'
import FolioSeal from './FolioSeal.vue'
import {characterPortrait} from '../utils/characterPortrait'
import {storyMoment} from '../utils/storyTime'
const props=defineProps({sessionId:String,playerId:String,viewMode:String,refreshKey:String,storySeconds:{type:Number,default:0},storyTime:String,worldSetting:Object,openingCharacters:{type:Array,default:()=>[]},initialQuery:Object})
function portrait(person){const opening=props.openingCharacters.find(p=>p.id===person.id);return characterPortrait({...person,introduction:opening?.introduction || ''},props.viewMode,props.worldSetting)}
function moment(seconds,label){const current=data.value?.storySeconds ?? props.storySeconds;return storyMoment(seconds,{currentSeconds:current,currentLabel:current===props.storySeconds?props.storyTime:undefined,recordedLabel:label})}
const data=ref(null),loading=ref(false),error=ref(''),page=ref(0)
const recordNames=computed(()=>dossierNamesFrom(data.value))
const bookSection=ref('records'),readingEntry=ref('')
const showPlots=computed({get:()=>bookSection.value==='plots',set:value=>{bookSection.value=value?'plots':'records'}})
const empty=()=>({keyword:'',roleId:'',fromSeconds:'',toSeconds:''})
const filters=reactive({...empty(),keyword:props.initialQuery?.keyword || '',roleId:props.initialQuery?.roleId || ''})
let applied={...filters},sequence=0,alive=true
const identity=()=>[props.sessionId,props.playerId,props.viewMode,props.refreshKey].join('|')
async function load(){
 const seq=++sequence,key=identity();data.value=null;error.value=''
 if(!props.sessionId)return
 const from=applied.fromSeconds===''?null:Number(applied.fromSeconds),to=applied.toSeconds===''?null:Number(applied.toSeconds)
 if([from,to].some(v=>v!==null && (!Number.isSafeInteger(v)||v<0)) || (from!==null && to!==null && from>to)){loading.value=false;error.value='请输入有效的世界时间范围。';return}
 loading.value=true
 try{
  const result=await http.get('/game/world-book/browse',{params:{sessionId:props.sessionId,keyword:applied.keyword,roleId:applied.roleId,fromSeconds:from??undefined,toSeconds:to??undefined,page:page.value}})
  if(!alive || seq!==sequence || key!==identity())return
  if(result.sessionId!==props.sessionId || result.viewMode!==props.viewMode){error.value='视角正在同步，请稍后刷新世诰。';return}
  data.value=result
 }catch(e){if(alive && seq===sequence && key===identity())error.value='世诰暂时未能读取，请点击刷新重试。'}
 finally{if(alive && seq===sequence)loading.value=false}
}
function focusRole(roleId){bookSection.value='records';filters.roleId=roleId;search()}
function readEntry(key){readingEntry.value=key;const article=[...document.querySelectorAll('.book-entry')].find(node=>node.dataset.entryKey===String(key));article?.scrollIntoView?.({block:'start',behavior:globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});article?.focus({preventScroll:true})}
watch(()=>props.initialQuery,query=>{if(!query)return;bookSection.value='records';Object.assign(filters,empty(),query);search()},{deep:true})
function search(){applied={...filters};page.value=0;load()}
function reset(){Object.assign(filters,empty());search()}
function goPage(value){page.value=value;load()}
function entryTitle(entry){return ({CHARACTER_DOSSIER:'角色附加表',CHARACTER_HISTORY:'人物过往',STORY_MILESTONE:'故事节点',MOVE:'行路',COMBAT:'交手',DIALOGUE:'交谈',INQUIRY:'打听',ITEM_GAIN:'获得物品',ITEM_LOSS:'交出物品',NPC_DEATH:'人物离世',NPC_SPAWN:'人物登场',DEATH:'终局'})[entry.kind] || entry.title}
function entryTime(entry){return entry.story_time || (entry.end_seconds!=null?moment(entry.end_seconds):entry.learned_tick!=null?`第 ${entry.learned_tick} 段留存记录`:'故事时刻未记录')}
function chapterProse(entry){
 if(entry.kind!=='CHAPTER')return entry.content
 return entry.content.split('\n').map(line=>{const colon=line.indexOf('：');if(colon<0)return line;const id=line.slice(0,colon);if(id==='narrator')return line.slice(colon+1);const role=data.value?.roles.find(r=>r.id===id);return role?`${role.name}：${line.slice(colon+1)}`:line}).join('\n')
}
watch(()=>[props.sessionId,props.playerId,props.viewMode],()=>{Object.assign(filters,empty());applied=empty();page.value=0},{flush:'sync'})
// Synchronous clearing prevents a single render of old omniscient data after mode/session/branch changes.
watch(identity,()=>{data.value=null;page.value=0;load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;sequence++;data.value=null})
</script>
