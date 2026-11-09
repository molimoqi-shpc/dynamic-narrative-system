<template>
  <main class="refraction-engine engine-demo" data-menu-destination="tide">
    <div class="demo-sheet">
      <nav class="demo-topline" aria-label="演示导航">
        <el-button text @click="$router.push('/home')">← 返回主菜单</el-button>
        <span class="demo-health" :class="{offline:healthLoaded&&!health.ok}">{{health.ok?'规则引擎已就绪':healthLoaded?'引擎暂时离线':'正在连接引擎'}}</span>
      </nav>
      <header class="demo-intro" data-arrival="title">
        <span class="demo-kicker">丰都 · 雨中交锋</span>
        <h1>人物行为解析模版</h1>
        <p>先读人物如何相互试探，再看数值如何解释这一局。</p>
        <div class="demo-notice">人物立场 · 行动对白 · 内心与后果</div>
      </header>
      <section class="demo-start" aria-label="运行演示">
        <p>丰都连月阴雨。一名行旅者当面质问雨神，旁观的博物神也加入了这场争辩。</p>
        <el-button class="demo-run" data-testid="run-walkthrough" type="primary" size="large" :loading="loadingWalkthrough" @click="runWalkthrough">{{walkthroughResult?'重看这一局':'展开雨中交锋'}}</el-button>
        <a v-if="showCommentary&&walkthroughResult?.analysis" class="demo-analysis-jump" href="#behavior-analysis-title">查看行为、六维与战斗解析 ↓</a>
        <p class="demo-context">展开故事后，可以关闭全知讲解，只看人物说出的话与当场行动。</p>
        <p v-if="walkthroughError" role="alert" class="engine-demo-error">{{walkthroughError}}</p>
      </section>
      <section v-if="walkthroughResult" class="demo-result" aria-labelledby="demo-story-heading">
        <div class="demo-result-heading">
          <div><span class="demo-kicker">逐幕阅读</span><h2 id="demo-story-heading">{{showCommentary?story?.title||'丰都雨局':'丰都雨局'}}</h2></div>
          <label class="demo-commentary-toggle"><input v-model="showCommentary" type="checkbox" role="switch" aria-label="全知讲解"/><span>全知讲解</span><small>{{showCommentary?'已开启':'已关闭'}}</small></label>
        </div>
        <p class="demo-view-hint">{{showCommentary?'公开动作、对白与真实意图对照阅读，沿着四幕交锋看清前因后果。':'只阅读在场可见的动作与说出的话。'}}</p>
        <p v-if="story?.premise" class="demo-premise">{{story.premise}}</p>
        <div v-if="story?.cast?.length" class="demo-cast" aria-label="在场人物">
          <article v-for="person in story.cast" :key="person.id" class="demo-person">
            <span class="demo-person-mark" aria-hidden="true">{{person.name?.slice(-1)}}</span>
            <h3>{{person.name}}</h3><p class="demo-person-identity">{{person.identity}}</p>
            <p v-if="person.publicGoal"><span>公开立场</span>{{person.publicGoal}}</p>
            <p v-if="showCommentary&&person.hiddenMotive" class="demo-private"><span>真实意图</span>{{person.hiddenMotive}}</p>
          </article>
        </div>
        <div v-if="story?.beats?.length" class="demo-scenes">
          <section v-for="(scene,sceneIndex) in scenes" :key="scene.id" class="demo-scene">
            <header class="demo-scene-title"><span aria-hidden="true">{{String(sceneIndex+1).padStart(2,'0')}}</span><h3>{{stageLabel(scene.stage)}}</h3><i aria-hidden="true"></i></header>
            <article v-for="(beat,index) in scene.beats" :key="beat.id||scene.id+'-'+index" class="demo-beat" :class="{'demo-beat-revealed':showCommentary&&beat.lieReason}" :style="{'--beat-delay':Math.min(index,3)*45+'ms'}">
              <p v-if="beat.action" class="demo-action">{{beat.action}}</p>
              <div v-if="beat.speech" class="demo-speech"><span class="demo-speaker">{{beat.speakerName||nameOf(beat.speakerId)}}</span><p>{{beat.speech}}</p><span v-if="showCommentary&&beat.lieReason" class="demo-lie-badge">此句掩饰了事实</span></div>
              <div v-if="showCommentary&&(beat.innerThought||beat.explanation||beat.lieReason)" class="demo-commentary">
                <p v-if="beat.innerThought"><span>{{beat.speakerName||nameOf(beat.speakerId)}}的内心</span>{{beat.innerThought}}</p>
                <p v-if="beat.explanation"><span>这一刻发生了什么</span>{{beat.explanation}}</p>
                <p v-if="beat.lieReason" class="demo-lie-reason"><span>为何这句话不实</span>{{beat.lieReason}}</p>
              </div>
            </article>
          </section>
        </div>
        <div v-else class="demo-legacy-lines">
          <article v-for="(line,index) in walkthroughResult.lines||[]" :key="line.godId+'-'+index" class="demo-beat">
            <div class="demo-speech"><span class="demo-speaker">{{nameOf(line.godId)}}</span><p>{{line.speech}}</p></div>
            <div v-if="showCommentary&&line.innerThought" class="demo-commentary"><p><span>人物内心</span>{{line.innerThought}}</p></div>
          </article>
        </div>
        <section v-if="showCommentary" class="demo-aftermath" aria-labelledby="demo-aftermath-heading">
          <div class="demo-aftermath-heading"><h3 id="demo-aftermath-heading">这一局留下了什么</h3><span class="demo-outcome">{{walkthroughResult.outcomeLabel}}</span></div>
          <p v-if="story?.ending" class="demo-ending">{{story.ending}}</p>
          <div v-if="statChanges.length" class="demo-stat-changes" aria-label="基础六维变化">
            <article v-for="entry in statChanges" :key="entry.id"><h4>{{nameOf(entry.id)}}</h4><p v-for="change in entry.changes" :key="change.key"><span>{{change.label}}</span><strong :class="{'demo-negative':change.delta<0}">{{signed(change.delta)}}</strong><small v-if="change.before!=null&&change.after!=null">实际值 {{change.before}} → {{change.after}}</small></p></article>
          </div>
          <p v-if="statChanges.length" class="demo-footnote">加减值作用于基础六维；实际值由成长曲线与协同公式重新计算，因此两者的差值可能不同。</p>
          <ul v-if="snapshot.relationshipDeltas?.length" class="demo-relations" aria-label="有向关系变化"><li v-for="(relation,index) in snapshot.relationshipDeltas" :key="index"><span>{{nameOf(relation.sourceId)}} → {{nameOf(relation.targetId)}}</span><strong :class="{'demo-negative':relation.delta<0}">信任 {{signed(relation.delta)}}</strong></li></ul>
          <p class="demo-footnote">箭头表示前者对后者的关系，不代表双方具有同样的信任。</p>
        </section>
      </section>
      <EngineDemoAnalysis v-if="showCommentary&&walkthroughResult?.analysis" :analysis="walkthroughResult.analysis"/>
      <details v-if="showCommentary" class="demo-evidence">
        <summary><span>演算依据</span><small>六维公式、第三方介入与原始事实</small><i aria-hidden="true">＋</i></summary>
        <div class="demo-evidence-body">
          <section v-if="formula"><h3>六维与介入公式</h3><dl class="demo-formula"><dt>六维实际值</dt><dd>{{formula.actual_stat_formula}}</dd><dt>根基“本”上限</dt><dd>{{formula.ben_max}}</dd><dt>介入阈值</dt><dd>{{formula.intervene_threshold}}</dd><dt>介入权重</dt><dd>能力 {{formula.intervention_triangle_weights?.ability}} · 意愿 {{formula.intervention_triangle_weights?.will}} · 收益 {{formula.intervention_triangle_weights?.gain}}</dd><dt>胜负差值阈值</dt><dd>{{formula.diff_clear_win_formula||formula.diff_clear_win}}</dd><dt>算计救援关系阈值</dt><dd>{{formula.exploit_rescue_trust_min}}</dd></dl><h4>旧版规则执行顺序</h4><ol class="demo-priority"><li v-for="(priority,index) in formula.resolution_priority_order||[]" :key="index">{{priorityLabel(priority)}}</li></ol></section>
          <section v-if="snapshot.interveners?.length"><h3>第三方为什么介入</h3><div class="demo-score" v-for="person in snapshot.interveners" :key="person.intervenerId"><p><strong>{{nameOf(person.intervenerId)}}</strong> 表面支持 {{nameOf(person.supports)}}</p><dl><div><dt>能力</dt><dd>{{person.ability}}</dd></div><div><dt>意愿</dt><dd>{{person.will}}</dd></div><div><dt>收益</dt><dd>{{person.gain}}</dd></div><div><dt>总分</dt><dd>{{person.total}}</dd></div></dl></div></section>
          <section v-if="snapshot.initialRelationships?.length"><h3>本局初始关系</h3><ul class="demo-raw-list"><li v-for="(relation,index) in snapshot.initialRelationships" :key="index">{{nameOf(relation.sourceId)}} → {{nameOf(relation.targetId)}}：信任 {{relation.trust}}<span v-if="relation.backstabbed">，有背刺历史</span></li></ul></section>
          <section v-if="walkthroughResult?.factsChinese?.length"><h3>引擎原始事实</h3><ul class="demo-raw-list"><li v-for="(fact,index) in walkthroughResult.factsChinese" :key="index">{{fact}}</li></ul></section>
          <p class="demo-footnote" v-if="!walkthroughResult">运行示例后，此处还会展示本局介入评分与实际结算事实。</p>
        </div>
      </details>
      <footer class="demo-footer"><span>本次演示不写入正式存档</span></footer>
    </div>
  </main>
</template>

<script setup>
import {ref,computed,onMounted} from 'vue'
import {ElMessage,ElButton} from 'element-plus'
import http from '../api'
import EngineDemoAnalysis from '../components/EngineDemoAnalysis.vue'
const health=ref({}),healthLoaded=ref(false),formula=ref(null)
const loadingWalkthrough=ref(false),walkthroughResult=ref(null),walkthroughError=ref(''),showCommentary=ref(true)
const story=computed(()=>walkthroughResult.value?.story)
const snapshot=computed(()=>walkthroughResult.value?.ruleSnapshot||{})
const defaultNames={player_01:'沈砚',rain_god:'雨神',museum_god:'博物神',farm_god:'农神'}
const statLabels={zhi:'知',hui:'慧',dou:'斗',wen:'闻',ben:'本',gui:'归'}
const publicStages={'缘起':'雨中的丰都','当面对峙':'当面对峙','借援夺势':'第三人的劝解','结果余波':'交锋暂歇'}
function nameOf(id){return story.value?.cast?.find(person=>person.id===id)?.name||defaultNames[id]||id||'旁白'}
function stageLabel(stage){return showCommentary.value?stage:(publicStages[stage]||'现场')}
function priorityLabel(value){const labels={BACKSTAB:'高阶算计救援',TALISMAN_NEUTRALIZE:'符咒抵消',THIRD_PARTY_INTERVENE:'普通第三方支持',ATTACKER_WIN:'双方属性对抗',BRIBE_ACCEPTED:'意图特有分支'};return Object.entries(labels).find(([key])=>String(value).includes(key))?.[1]||value}
function signed(number){return Number(number)>0?'+'+number:String(number)}
const scenes=computed(()=>{
  const groups=[]
  for(const beat of story.value?.beats||[]){
    let group=groups[groups.length-1]
    if(!group||group.stage!==beat.stage){group={id:beat.id||'scene-'+groups.length,stage:beat.stage,beats:[]};groups.push(group)}
    group.beats.push(beat)
  }
  return groups
})
const statChanges=computed(()=>Object.entries(snapshot.value.statDeltaPerGod||{}).map(([id,delta])=>({id,changes:Object.entries(statLabels).filter(([key])=>Number(delta[key])).map(([key,label])=>({key,label,delta:delta[key],before:snapshot.value.before_actuals_per_god?.[id]?.[key],after:snapshot.value.after_actuals_per_god?.[id]?.[key]}))})).filter(entry=>entry.changes.length))
async function loadHealth(){try{health.value=await http.get('/engine/health')}catch(e){ElMessage.error('引擎离线：'+(e.response?.data?.message||e.message))}finally{healthLoaded.value=true}}
async function loadFormula(){try{formula.value=await http.get('/engine/demo/formula-constants')}catch(e){ElMessage.error('加载公式失败：'+(e.response?.data?.message||e.message))}}
async function runWalkthrough(){
  if(loadingWalkthrough.value)return
  loadingWalkthrough.value=true;walkthroughError.value=''
  try{walkthroughResult.value=await http.post('/engine/demo/fengdu-walkthrough',{})}
  catch(e){walkthroughError.value='示例运行失败：'+(e.response?.data?.message||e.message||'请稍后重试');ElMessage.error(walkthroughError.value)}
  finally{loadingWalkthrough.value=false}
}
onMounted(()=>{loadHealth();loadFormula()})
</script>

<style scoped>
.engine-demo{min-height:100vh;padding:28px 24px 34px;background:radial-gradient(ellipse at 85% 0%,#b6b9bd23,transparent 47%),var(--myth-bg,#f4f3ef);color:var(--myth-ink,#303d46)}.demo-sheet{max-width:1040px;margin:auto}.demo-topline{display:flex;align-items:center;justify-content:space-between;gap:16px}.demo-topline :deep(button){color:var(--myth-bronze-hi,#667680)}.demo-health{font-size:11px;letter-spacing:1px;color:#64776d}.demo-health::before{content:'';display:inline-block;width:5px;height:5px;border-radius:50%;margin:0 8px 2px 0;background:#7f9483}.demo-health.offline{color:#a34f48}
.demo-intro{position:relative;margin:25px 0 0;padding:0 0 22px;border-bottom:1px solid var(--myth-divider,#d7d9d6)}.demo-intro::after{content:'雨';position:absolute;right:10px;top:-22px;font-size:114px;line-height:1;color:#6c7b8610;pointer-events:none}.demo-kicker{display:block;letter-spacing:3px;font-size:11px;color:var(--myth-bronze-hi,#667680)}.demo-intro h1{font-size:clamp(28px,4vw,41px);letter-spacing:5px;font-weight:500;line-height:1.5;margin:12px 0}.demo-intro>p{font-size:14px;line-height:1.9;margin:0;opacity:.76}.demo-notice{font-size:11px;letter-spacing:1px;margin-top:20px;color:#7c8588}.demo-start{padding:18px 0 24px}.demo-start>p:first-child{font-size:15px;line-height:2;margin:0 0 17px;max-width:710px}.demo-run{min-height:41px;letter-spacing:2px;padding:11px 25px;border-radius:3px;background:#4e606b;border-color:#4e606b}.demo-run:hover{background:#3a4e5c;border-color:#3a4e5c}.demo-analysis-jump{display:inline-block;margin-left:18px;color:#657e8d;font-size:12px;line-height:1.9;text-decoration:none;border-bottom:1px solid #b3c2ca;padding:3px 0}.demo-analysis-jump:hover{color:#324e61;border-color:#698593}.demo-analysis-jump:focus-visible{outline:2px solid #7e98a7;outline-offset:4px}.demo-context{font-size:12px;line-height:1.9;color:#7a8389;max-width:750px;margin:14px 0 0}.engine-demo-error{color:#a34f48;font-size:13px;line-height:1.8;margin:15px 0}
.demo-result{background:var(--myth-bg-card,#fafaf8);border:1px solid var(--myth-divider,#d7d9d6);padding:30px clamp(18px,4vw,40px);box-shadow:0 16px 45px #283b4710}.demo-result-heading{display:flex;gap:20px;align-items:center;justify-content:space-between}.demo-result-heading h2{margin:9px 0 0;font-size:25px;letter-spacing:2px;font-weight:500}.demo-commentary-toggle{display:flex;align-items:center;gap:8px;cursor:pointer;font-size:12px;flex-wrap:wrap;flex-shrink:0}.demo-commentary-toggle input{accent-color:#536a76;width:15px;height:15px;margin:0;cursor:pointer}.demo-commentary-toggle small{font-size:10px;color:#7e898b}.demo-view-hint{font-size:11px;line-height:1.9;color:#839094;margin:15px 0 24px}.demo-premise{font-size:15px;line-height:2.1;padding:18px 22px;margin:0 0 27px;border-left:2px solid #9aa8ac;background:linear-gradient(90deg,#b2bdc112,transparent)}
.demo-cast{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-bottom:35px}.demo-person{padding:16px 15px 12px;position:relative;border-top:1px solid #a8b3b7;border-bottom:1px solid #d9dedd;background:linear-gradient(140deg,#dce3e51d,transparent);overflow:hidden}.demo-person-mark{position:absolute;right:10px;top:1px;font-size:55px;color:#68808e0e;line-height:1;pointer-events:none}.demo-person h3{font-size:18px;font-weight:500;letter-spacing:2px;margin:0 0 5px}.demo-person p{font-size:12px;line-height:1.9;margin:10px 0}.demo-person p span{display:block;font-size:10px;letter-spacing:1px;color:#8b969a;margin-bottom:3px}.demo-person .demo-person-identity{font-size:11px;color:#6c7b83;margin:0 0 14px}.demo-person .demo-private{border-top:1px dashed #c4cdce;padding-top:10px;color:#7b655e}
.demo-scene{margin:0 0 30px}.demo-scene-title{display:flex;align-items:center;gap:12px;margin-bottom:19px}.demo-scene-title>span{font-family:Georgia,serif;font-size:27px;font-style:italic;color:#9ca9af;line-height:1}.demo-scene-title h3{font-size:14px;font-weight:500;letter-spacing:2px;margin:0;white-space:nowrap}.demo-scene-title i{height:1px;background:linear-gradient(90deg,#bcc6c9,transparent);flex:1}.demo-beat{margin:0 0 21px;animation:demo-beat-arrive .35s both;animation-delay:var(--beat-delay,0ms)}.demo-action{line-height:2.1;font-size:14px;color:#69767c;margin:0 0 11px;white-space:pre-line}.demo-speech{position:relative;display:grid;grid-template-columns:64px minmax(0,1fr);gap:12px;padding:13px 0}.demo-speaker{font-size:13px;color:#61737d;letter-spacing:1px;line-height:2.1}.demo-speech>p{font-size:16px;line-height:1.95;margin:0;white-space:pre-line}.demo-lie-badge{grid-column:2;font-size:10px;letter-spacing:1px;line-height:1.8;color:#9a6255;justify-self:start;border-bottom:1px dotted #ba9183}.demo-commentary{margin:5px 0 0 76px;padding:3px 0 3px 16px;border-left:1px solid #c1cbce;color:#74828a}.demo-commentary p{font-size:12px;line-height:1.95;margin:8px 0;white-space:pre-line}.demo-commentary p>span{font-size:10px;color:#86969c;letter-spacing:1px;display:block;margin-bottom:2px}.demo-commentary .demo-lie-reason{color:#91695f}.demo-beat-revealed .demo-commentary{border-color:#ba9b8e}.demo-legacy-lines .demo-beat{border-bottom:1px solid #d7dedd;padding-bottom:15px}
.demo-aftermath{padding-top:23px;border-top:1px solid #c9d0d0;margin-top:28px}.demo-aftermath-heading{display:flex;justify-content:space-between;align-items:center;gap:15px;flex-wrap:wrap}.demo-aftermath-heading h3{font-weight:500;letter-spacing:2px;font-size:17px;margin:0}.demo-outcome{font-size:11px;letter-spacing:1px;border:1px solid #c8b6a4;color:#8d735c;padding:5px 10px}.demo-ending{font-size:14px;line-height:2.1;margin:18px 0}.demo-stat-changes{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:20px 0 12px}.demo-stat-changes article{border:1px solid #d8dfdd;padding:13px 15px}.demo-stat-changes h4{font-weight:500;font-size:13px;letter-spacing:1px;margin:0 0 10px}.demo-stat-changes p{display:flex;align-items:center;gap:10px;font-size:13px;margin:7px 0;flex-wrap:wrap}.demo-stat-changes strong,.demo-relations strong{color:#588073;font-weight:500}.demo-stat-changes small{font-size:10px;color:#7b8b91}.demo-negative{color:#a66a5d!important}.demo-footnote{font-size:11px;color:#849095;line-height:1.9;margin:12px 0}.demo-relations{list-style:none;padding:13px 0 0;border-top:1px dashed #d3dadb;margin:17px 0 0;display:flex;gap:10px 27px;flex-wrap:wrap}.demo-relations li{display:flex;gap:12px;font-size:12px;line-height:1.9}
.demo-evidence{border-top:1px solid #c9d1d2;border-bottom:1px solid #c9d1d2;margin-top:28px}.demo-evidence summary{display:flex;gap:17px;align-items:center;list-style:none;padding:18px 0;cursor:pointer}.demo-evidence summary::-webkit-details-marker{display:none}.demo-evidence summary>span{font-size:14px;letter-spacing:2px}.demo-evidence summary small{font-size:11px;color:#8c979b}.demo-evidence summary i{margin-left:auto;font-style:normal;color:#7d8f98;font-size:18px}.demo-evidence[open] summary i{transform:rotate(45deg)}.demo-evidence-body{padding:3px 0 25px}.demo-evidence-body>section{padding:17px 20px;margin:0 0 16px;background:#b1bfc30e;border:1px solid #d8dedd}.demo-evidence-body h3{font-size:14px;font-weight:500;margin:0 0 15px;letter-spacing:1px}.demo-evidence-body h4{font-size:12px;font-weight:500;margin:20px 0 12px}.demo-formula{display:grid;grid-template-columns:155px minmax(0,1fr);gap:10px 20px;font-size:12px;line-height:1.9;margin:0}.demo-formula dt{color:#77888f}.demo-formula dd{margin:0;overflow-wrap:anywhere}.demo-priority,.demo-raw-list{padding-left:20px;margin:0;color:#73838a;font-size:12px;line-height:2}.demo-raw-list li{margin:8px 0;overflow-wrap:anywhere}.demo-score{border-top:1px solid #d6dedf;padding:13px 0 0}.demo-score>p{font-size:12px;margin:0 0 12px;color:#7a8a91}.demo-score strong{color:#4e6876;font-weight:500;margin-right:12px}.demo-score dl{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:0 0 8px}.demo-score dt{font-size:10px;color:#8c9b9f;letter-spacing:1px}.demo-score dd{font-size:20px;color:#526b79;font-family:Georgia,serif;margin:7px 0 0}.demo-footer{display:flex;justify-content:space-between;gap:14px;flex-wrap:wrap;margin-top:20px;font-size:10px;letter-spacing:1px;color:#89979b}
@keyframes demo-beat-arrive{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}
@media(max-width:700px){.engine-demo{padding:17px 15px 27px}.demo-intro{margin-top:31px}.demo-cast{grid-template-columns:1fr;gap:11px}.demo-person{padding:13px 15px}.demo-person h3{font-size:16px}.demo-person p{margin:8px 0}.demo-person .demo-person-identity{margin-bottom:8px}.demo-person p span{display:inline;margin-right:9px}.demo-result-heading{align-items:flex-start;flex-direction:column;gap:16px}.demo-result-heading h2{font-size:22px}.demo-commentary-toggle{align-self:flex-end}.demo-view-hint{margin-top:12px}.demo-premise{font-size:14px;padding:13px 15px}.demo-speech{grid-template-columns:52px minmax(0,1fr);gap:9px}.demo-speech>p{font-size:15px}.demo-speaker{font-size:12px}.demo-commentary{margin-left:61px;padding-left:12px}.demo-stat-changes{grid-template-columns:1fr}.demo-stat-changes article{display:flex;gap:15px;align-items:baseline;flex-wrap:wrap}.demo-stat-changes h4{margin:0;min-width:44px}.demo-stat-changes p{margin:0}.demo-evidence summary{gap:12px;flex-wrap:wrap}.demo-evidence summary small{font-size:10px}.demo-formula{grid-template-columns:1fr;gap:3px}.demo-formula dd{margin-bottom:8px}.demo-raw-list{padding-left:14px}.demo-aftermath-heading h3{font-size:15px}}
@media(prefers-reduced-motion:reduce){.demo-beat{animation:none}.demo-evidence summary i{transition:none}}
</style>





