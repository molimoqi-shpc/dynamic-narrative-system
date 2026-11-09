<template>
 <section class="plot-book" aria-label="重要剧情与伏笔">
  <p class="plot-note">{{viewMode==='OMNISCIENT'?'展示已发生的剧情、铺垫与回收；未来创作构思不会作为故事事实展示。':'仅整理主角已经获知的剧情与线索，未揭露的真相和幕后进展不会显示。'}}日常小事不单独编号。</p>
  <form class="plot-search" @submit.prevent="search">
   <label>剧情或线索<input v-model="filters.keyword" maxlength="120" placeholder="输入已知的剧情、线索关键词" /></label>
   <label>剧情编号<input v-model="filters.eventId" maxlength="40" placeholder="按剧情编号直接查阅" /></label>
   <label>剧情类别<select v-model="filters.category"><option value="">全部类别</option><option v-for="(label,key) in categories" :key="key" :value="key">{{label}}</option></select></label>
   <button :disabled="loading" type="submit">查询剧情</button><button type="button" @click="reset">全部剧情</button>
  </form>
  <p v-if="error" role="alert">{{error}}</p><p v-if="loading" role="status">正在查阅剧情…</p>
  <template v-if="data">
   <p v-if="!data.events.length" class="plot-note">暂无符合条件的剧情记录。旧存档会在后续定稿归档时整理相关的重要剧情。</p>
   <article v-for="event in data.events" :key="event.eventId" class="plot-event">
    <header><h3>{{event.title}}</h3><span>{{categories[event.category] || '剧情'}} · {{statusLabel(event.status)}}</span></header>
    <button class="plot-ref" type="button" @click="selectEvent(event.eventId)">{{event.eventId}}</button>
    <p v-if="event.summary" class="plot-prose">{{event.summary}}</p>
    <template v-if="viewMode==='OMNISCIENT'">
     <p v-if="event.openQuestion"><strong>当前问题：</strong>{{event.openQuestion}}</p>
     <details v-if="event.establishedFacts?.length"><summary>已确立的起因与事实</summary><ul><li v-for="(fact,index) in event.establishedFacts" :key="index">{{typeof fact==='string'?fact:fact.text}}<small v-if="fact.source"> · {{fact.source}}</small></li></ul></details>
     <p v-if="event.development"><strong>剧情发展：</strong>{{event.development}}</p><p v-if="event.outcome"><strong>阶段结果：</strong>{{event.outcome}}</p>
    </template>
    <button v-if="event.source?.logId" type="button" @click="readSource(event.source.logId)">查阅原章节 · 第 {{event.source.round}} 段</button>
    <div v-if="event.foreshadows?.length" class="plot-threads"><h4>{{viewMode==='OMNISCIENT'?'重要伏笔与回收':'已知线索与未解问题'}}</h4>
     <details v-for="thread in event.foreshadows" :key="thread.threadId"><summary>{{thread.title}} <span>· {{statusLabel(thread.status)}}</span></summary>
      <p class="plot-note">{{thread.threadId}}</p><p v-if="thread.summary" class="plot-prose">{{thread.summary}}</p>
      <template v-if="viewMode==='OMNISCIENT'"><p v-if="thread.setup"><strong>原始铺垫：</strong>{{thread.setup}}</p><p v-if="thread.question"><strong>具体问题：</strong>{{thread.question}}</p>
       <ul v-if="thread.developments?.length"><li v-for="(step,index) in thread.developments" :key="index">{{typeof step==='string'?step:step.text}}</li></ul><p v-if="thread.payoff"><strong>实际回收与后果：</strong>{{thread.payoff}}</p>
      </template>
      <button v-if="thread.source?.logId" type="button" @click="readSource(thread.source.logId)">查阅原章节</button>
     </details>
     <button v-if="threadPage>0" type="button" :disabled="loading" @click="turnThreads(event.eventId,-1)">上一页线索</button><button v-if="event.threadHasMore" type="button" :disabled="loading" @click="turnThreads(event.eventId,1)">更多线索</button>
    </div>
   </article>
   <nav v-if="data.total>0" aria-label="剧情分页"><button type="button" :disabled="loading || page===0" @click="turnPage(-1)">上一页剧情</button><span>{{data.total}} 项剧情 · 第 {{page+1}} 页</span><button type="button" :disabled="loading || !data.hasMore" @click="turnPage(1)">下一页剧情</button></nav>
  </template>
  <section v-if="sourceLoading || source || sourceError" ref="sourceLeaf" class="plot-source" aria-label="剧情原章节"><header><h3>原章节</h3><button type="button" @click="closeSource">收起原文</button></header><p v-if="sourceLoading">正在读取原文…</p><p v-if="sourceError" role="alert">{{sourceError}}</p><p v-if="source" class="plot-prose">{{originalProse(source.story)}}</p></section>
 </section>
</template>
<script setup>
import {ref,reactive,watch,onBeforeUnmount,nextTick} from 'vue'
import http from '../api'
const props=defineProps({sessionId:String,playerId:String,viewMode:String,refreshKey:String})
const categories={INVESTIGATION:'调查与真相',RELATIONSHIP:'人物关系',CONFLICT:'冲突与对抗',JOURNEY:'重要旅程',OTHER:'其他剧情'}
const statusLabel=value=>({OPEN:'尚在发展',DEVELOPING:'已有进展',RESOLVED:'已有阶段结果',FAILED:'未能达成',DORMANT:'暂时搁置',PAID_OFF:'已兑现',CLOSED:'已收束'})[value] || value || '已有记录'
const originalProse=text=>(text || '').split('\n').map(line=>line.startsWith('narrator：')?line.slice('narrator：'.length):line).join('\n')
const empty=()=>({keyword:'',eventId:'',category:''})
const filters=reactive(empty()),data=ref(null),loading=ref(false),error=ref(''),page=ref(0),threadPage=ref(0)
const source=ref(null),sourceLoading=ref(false),sourceError=ref(''),sourceLeaf=ref(null)
let applied=empty(),sequence=0,sourceSequence=0,alive=true
const identity=()=>[props.sessionId,props.playerId,props.viewMode,props.refreshKey].join('|')
function closeSource(){sourceSequence++;source.value=null;sourceError.value='';sourceLoading.value=false}
async function load(){
 const seq=++sequence,key=identity();data.value=null;error.value='';loading.value=false;closeSource()
 if(!props.sessionId)return
 loading.value=true
 try{
  const result=await http.get('/game/world-book/plots',{params:{sessionId:props.sessionId,...applied,page:page.value,threadPage:threadPage.value}})
  if(!alive || seq!==sequence || key!==identity())return
  if(result.sessionId!==props.sessionId || result.viewMode!==props.viewMode){error.value='视角正在同步，请稍后重试。';return}
  data.value=result
 }catch(e){if(alive && seq===sequence && key===identity())error.value='剧情资料暂时无法读取，请重新查询。'}
 finally{if(alive && seq===sequence)loading.value=false}
}
function search(){applied={...filters};page.value=0;threadPage.value=0;load()}
function reset(){Object.assign(filters,empty());search()}
function selectEvent(eventId){filters.eventId=eventId;search()}
function turnPage(delta){page.value+=delta;threadPage.value=0;load()}
function turnThreads(eventId,delta){applied={...applied,eventId};filters.eventId=eventId;page.value=0;threadPage.value+=delta;load()}
async function readSource(logId){
 const seq=++sourceSequence,key=identity();source.value=null;sourceError.value='';sourceLoading.value=true
 await nextTick();if(!alive || seq!==sourceSequence || key!==identity())return;sourceLeaf.value?.scrollIntoView?.({block:'start',behavior:globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'})
 try{
  const result=await http.get('/game/world-book/plots/passage',{params:{sessionId:props.sessionId,logId}})
  if(alive && seq===sourceSequence && key===identity() && result.sessionId===props.sessionId)source.value=result
 }catch(e){if(alive && seq===sourceSequence && key===identity())sourceError.value='原章节暂时无法读取。'}
 finally{if(alive && seq===sourceSequence)sourceLoading.value=false}
}
watch(()=>[props.sessionId,props.playerId,props.viewMode],()=>{Object.assign(filters,empty());applied=empty()},{flush:'sync'})
watch(identity,()=>{data.value=null;page.value=0;threadPage.value=0;load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;sequence++;closeSource();data.value=null})
</script>
<style scoped>
.plot-book{line-height:1.8}.plot-note,.plot-event small{font-size:.82rem;color:#aaa794}.plot-search{display:flex;gap:.7rem;flex-wrap:wrap;margin:1rem 0}.plot-search label{display:flex;flex-direction:column;gap:.3rem;font-size:.83rem;color:#bdae83;flex:1;min-width:140px}.plot-search input,.plot-search select{min-width:0;padding:.6rem;background:#191e21;border:1px solid #aa94683d;border-radius:6px;color:#e5dfce;font:inherit}.plot-book button{align-self:end;background:#b89c5520;border:1px solid #b89c5550;color:#d8c18a;border-radius:5px;padding:.4rem .7rem;cursor:pointer}.plot-book button:disabled{opacity:.4;cursor:default}.plot-event{margin:1rem 0;padding:1rem;border:1px solid #c5ac6940;border-radius:8px;background:#a2915909}.plot-event header,.plot-source header{display:flex;justify-content:space-between;align-items:center;gap:.7rem;flex-wrap:wrap}.plot-event h3{margin:0;font-family:serif;font-size:1.15rem}.plot-event header span,.plot-threads summary span{font-size:.8rem;color:#bdae83}.plot-event .plot-ref{font-size:.75rem;border:0;background:transparent;padding:.2rem 0;overflow-wrap:anywhere;text-align:left}.plot-threads{margin-top:.8rem;border-top:1px solid #c5ac6925}.plot-threads h4{font-weight:500}.plot-book details{padding:.3rem 0}.plot-book summary{cursor:pointer;color:#c8b47f}.plot-book nav{display:flex;justify-content:center;align-items:center;gap:.8rem;font-size:.8rem;margin:1rem 0}.plot-prose{white-space:pre-wrap;overflow-wrap:anywhere;font-family:serif;line-height:1.9}.plot-source{border-top:1px solid #c5ac6950;margin-top:1.5rem;padding-top:.7rem}
</style>
