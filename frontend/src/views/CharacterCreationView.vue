<template>
 <main class="creation-page refraction-creation creation-edition" data-menu-destination="angel">
  <div class="creation-masthead"><CompassMark/><span>此生行记<small>PROLOGUE / 序章</small></span><FlightMark/></div>
  <div class="creation-shell">
   <header class="creation-title-block" data-arrival="title"><div><p class="edition-eyebrow">一卷将启 / {{String(currentChapter+1).padStart(2,'0')}}</p><h1>旅程之始<span aria-hidden="true">序</span></h1><p>先看见这个世界，再决定你是谁。</p></div><button class="edition-text-link" @click="returnHome">返回主菜单 <span>↗</span></button></header>
   <ol class="creation-chapters" data-arrival="item" aria-label="开局进程"><li v-for="(chapter,index) in creationChapters" :key="chapter.name" :class="{active:currentChapter===index,complete:currentChapter>index}" :aria-current="currentChapter===index?'step':undefined"><span>{{String(index+1).padStart(2,'0')}}</span><div><strong>{{chapter.name}}</strong><small>{{chapter.caption}}</small></div><i aria-hidden="true">{{currentChapter>index?'✓':'◇'}}</i></li></ol>
   <section v-if="step===0 && !worldReviewed" class="creation-world" :aria-busy="loadingWorld || loadingStart">
    <div class="creation-opening-art" data-arrival="ornament" aria-hidden="true"><img src="/art/illuminated-v1/prologue-wing.webp" alt=""/><span class="opening-art-line"/><span class="opening-art-number">I</span></div>
    <div class="creation-world-copy" data-arrival="body"><p class="world-intro-kicker"><span/> 此生所在 / 卷首</p>
     <template v-if="worldSetting?.introduction"><h2>{{worldSetting.title}}</h2><p class="world-genre">{{[worldSetting.genre,worldSetting.era].filter(Boolean).join(' · ')}}</p><div class="world-introduction"><p v-for="(paragraph,i) in worldSetting.introduction.split(/\n+/).filter(Boolean)" :key="i">{{paragraph}}</p></div><button class="world-start-questions edition-primary" @click="worldReviewed=true">了解世界，建立角色 <span>↗</span></button><p class="world-fixed-note">此间天地已留存。后来的相逢，都将从这里展开。</p></template>
     <template v-else><FolioSeal kind="world" class="world-await-seal"/><h2>{{loadingWorld || loadingStart?'此间天地，正在展开':'选择此生的天地'}}</h2><p class="world-pending-copy" role="status">{{worldError || (loadingWorld?'主创正在写就时代与风土，世界生成后会固定保存。':'先选择世界观，主创会在这个范围内生成独属于本局的世界。')}}</p><fieldset class="world-theme-options" :disabled="loadingWorld || loadingStart"><legend>世界观</legend><label v-for="theme in worldThemes" :key="theme.key" :class="{selected:worldTheme===theme.key}"><input type="radio" name="world-theme" v-model="worldTheme" :value="theme.key"/><strong>{{theme.name}}</strong><small>{{theme.caption}}</small></label></fieldset><span v-if="loadingWorld || loadingStart" class="edition-loading-line" aria-hidden="true"/><button v-if="!loadingWorld && !loadingStart" class="edition-primary" @click="beginWorld">{{worldError?'重试生成世界':'生成这个世界'}} ↗</button></template>
    </div>
   </section>
   <Transition name="chapter" mode="out-in">
    <section v-if="step===0 && worldReviewed" v-loading="loadingStart" class="creation-questionnaire">
     <aside class="question-frontispiece"><FolioSeal kind="identity"/><span class="folio-volume">II</span><p class="edition-eyebrow">名字，所重，与抉择</p><h2>你将如何<br/>走入此间？</h2><p>一个名字，一段来路。<br/>写下你所珍视的，<br/>以及面对未知时的选择。</p><FlightMark/><small>从自己的话开始。</small></aside>
     <div class="question-leaves"><header><p class="edition-eyebrow">问询 / 留下你的来路</p><h2>写下自己</h2><span>无需给出标准答案。</span></header><div v-if="questions.length===0 && !loadingStart" class="edition-empty">三问暂未传来。<el-button text @click="loadStart">重新加载</el-button></div><template v-else><el-form label-position="top"><div v-for="(q,i) in questions" :key="i" class="question-leaf"><span class="question-index" aria-hidden="true">{{String(i+1).padStart(2,'0')}}</span><el-form-item :label="`第${i+1}问 · ${q}`"><el-input v-model="answers[i]" type="textarea" :rows="2" maxlength="2000" show-word-limit :disabled="loadingAnswer || talents.length>0" :placeholder="`作答第${i+1}问...`"/></el-form-item></div></el-form><footer class="edition-action-row"><p>这些选择，将成为你此生的起笔。</p><el-button type="primary" :loading="loadingAnswer" :disabled="answers.some(a=>!a || !a.trim())" @click="submitAnswers">{{talents.length?'返回天赋选择':'确立角色'}} <span>↗</span></el-button></footer></template></div>
    </section>
    <section v-else-if="step===1" v-loading="loadingAnswer" class="creation-talents"><header class="talent-frontmatter"><p class="edition-eyebrow">抉择 / 三种可能，一段此生</p><h2>择一相承</h2><div v-if="personalityTags.length" class="creation-traits"><span v-for="tag in personalityTags" :key="tag">{{tag}}</span></div><p>所选天赋将在诞生时生效。特殊作用与边界，以卡面记载为准。</p></header><div class="talent-grid" role="radiogroup" aria-label="选择入世天赋"><el-card v-for="(t,index) in talents" :key="t.talentId" shadow="never" v-paper-light class="talent-contract tactile-surface" :class="{'talent-selected':selectedTalentId===t.talentId}" role="radio" :tabindex="loadingSelect?-1:0" :aria-disabled="loadingSelect" :aria-checked="selectedTalentId===t.talentId" @click="!loadingSelect && (selectedTalentId=t.talentId)" @keydown.enter.prevent="!loadingSelect && (selectedTalentId=t.talentId)" @keydown.space.prevent="!loadingSelect && (selectedTalentId=t.talentId)"><PaperFinish/><div class="talent-contract-art" :class="`talent-art-${index%3}`"><FolioSeal :kind="['insight','resolve','world'][index%3]"/><span>{{String(index+1).padStart(2,'0')}}</span></div><div class="talent-contract-copy"><p class="edition-eyebrow">入世之契 <span>强度 {{t.powerLevel}}</span></p><h3>{{t.talentName}}</h3><p>{{visibleText(t.talentDescription) || '（无描述）'}}</p><footer><span>{{selectedTalentId===t.talentId?'已选定此契':'选择这份天赋'}}</span><i aria-hidden="true">{{selectedTalentId===t.talentId?'✓':'↗'}}</i></footer></div></el-card></div><p v-if="!talents.length && !loadingAnswer" class="edition-empty">天赋尚未传来，请返回三问重试。</p><footer class="edition-action-row"><el-button :disabled="loadingSelect" @click="step=0">← 查看三问</el-button><el-button type="primary" :loading="loadingSelect" :disabled="!selectedTalentId" @click="selectTalent">以此入世 ↗</el-button></footer></section>
    <section v-else-if="step===2" v-loading="loadingSelect" class="creation-birth"><aside aria-hidden="true"><img src="/art/illuminated-v1/prologue-wing.webp" alt=""/><span>IV</span></aside><article v-if="birthStory"><p class="edition-eyebrow">诞生 / 此生第一笔</p><h2>从此，故事里有了你。</h2><div class="birth-prose">{{birthStory}}</div><div v-if="initialLocation" class="birth-location"><FolioSeal kind="world"/><div><small>初至此间</small><h3>{{initialLocation.locationName}}</h3><p v-if="storyTime">{{storyTime}}</p><p v-if="initialLocation.sceneDescription">{{initialLocation.sceneDescription}}</p></div></div><details v-if="initialLocation?.presentCharacters?.length" class="birth-cast" open><summary>初见人物</summary><p v-for="person in initialLocation.presentCharacters" :key="person.id"><b>{{person.name}}</b> · {{person.introduction}}</p></details><el-button type="primary" size="large" @click="enterGame">开始此生旅程 ↗</el-button></article></section>
   </Transition>
   <nav class="creation-return-bar" aria-label="开局返回导航"><button @click="returnHome">← 返回主菜单</button><button v-if="step===0 && worldReviewed && worldSetting?.introduction" @click="worldReviewed=false">上一步 · 查看世界</button><small>{{sessionStore.archiveReady===false?'世界尚未生成，不建立新存档':'已生成的内容会保留，可从存档继续'}}</small></nav>
  </div>
 </main>
</template>
<script setup>
import PaperFinish from '../components/PaperFinish.vue'
import {paperLight as vPaperLight} from '../utils/paperLight'
import AmbientParticles from '../components/AmbientParticles.vue'
import CompassMark from '../components/CompassMark.vue'
import FlightMark from '../components/FlightMark.vue'
import FolioSeal from '../components/FolioSeal.vue'
import {visibleText} from '../utils/visibleText'
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import {requestIdentity} from '../utils/requestIdentity.js'
import { useRouter } from 'vue-router'
import { ElMessage, ElButton, ElCard, ElDivider, ElForm, ElFormItem, ElInput, ElStep, ElSteps, ElTag } from 'element-plus'
import http from '../api'
import { useSessionStore } from '../store/session'
import { useViewModeStore } from '../store/viewMode'

const router = useRouter()
const sessionStore = useSessionStore()
const viewMode = useViewModeStore()

let viewAlive=true,creationSequence=0
onBeforeUnmount(()=>{viewAlive=false;creationSequence++})
function beginCreationRequest(){const sequence=++creationSequence;const valid=requestIdentity(sessionStore,()=>viewAlive && sequence===creationSequence);return {valid,owned:()=>viewAlive && sequence===creationSequence}}
const step = ref(0)
const currentChapter=computed(()=>step.value===0?(worldReviewed.value?1:0):step.value+1)
const creationChapters=[{name:'世界',caption:'此生所在'},{name:'问询',caption:'写下自己'},{name:'抉择',caption:'择一相承'},{name:'诞生',caption:'步入故事'}]
const loadingStart = ref(false)
const loadingAnswer = ref(false)
const loadingSelect = ref(false)

// Step 0
const worldSetting=ref(null),worldReviewed=ref(false),loadingWorld=ref(false),worldError=ref('')
const worldTheme=ref('WESTERN')
const worldThemes=[{key:'WESTERN',name:'西幻',caption:'异族、城邦与未知之境'},{key:'EASTERN',name:'中幻',caption:'山川、江湖与志怪传说'},{key:'MODERN',name:'现代',caption:'城市、日常与未解之事'}]
const questions = ref([])
const answers = ref(['', '', ''])
// Step 1
const personalityTags = ref([])
const talents = ref([])
const selectedTalentId = ref(null)
// Step 2
const birthStory = ref('')
const initialLocation = ref(null)
const storyTime = ref('')
const sceneId = ref(null)
const playerGodId = ref(null)

function handleErr(e, prefix = '操作失败') {
  const msg = e.response?.data?.message || e.message || prefix
  ElMessage.error(msg)
}

function releaseEmptyDraft(sessionId,playerId){if(sessionId && playerId)void http.post('/game/creation/abandon',{sessionId,playerId},{timeout:2000}).catch(()=>{})}
function returnHome(){
  if(sessionStore.archiveReady===false){const {sessionId,playerId}=sessionStore;releaseEmptyDraft(sessionId,playerId);sessionStore.clearSession();sessionStore.setActiveSession({playerId})}
  return router.push('/home')
}

async function loadStart() {
  if (!sessionStore.playerId) {router.replace('/login');return}
  const request=beginCreationRequest()
  const ownerId=sessionStore.playerId
  loadingStart.value = true
  try {
    const selected=sessionStore.status==='CREATING'?sessionStore.sessionId:!sessionStore.sessionId?sessionStore.rememberedSessionId():null
    const r = selected
      ? await http.get('/game/creation',{params:{sessionId:selected,playerId:sessionStore.playerId}})
      : await http.post('/game/start',{playerId:sessionStore.playerId,viewMode:viewMode.mode})
    if(!request.valid()){if(r.worldPending || r.status==='WORLD_PENDING')releaseEmptyDraft(r.sessionId,ownerId);return}
    if(r.answers)answers.value=r.answers
    if (r.status==='TALENT_GENERATED') {
      personalityTags.value=r.personalityTags || []
      talents.value=r.talents || []
      step.value=1
    }
    sessionStore.setActiveSession({ sessionId: r.sessionId, status: 'CREATING', archiveReady:r.archiveReady ?? !(r.worldPending || r.status==='WORLD_PENDING') })
    if(r.status==='COMPLETED')applyBirth(r)
    worldSetting.value=r.worldSetting || null
    questions.value = r.questions || []
    // Initialization assigns a new session ID. Capture its ownership afterwards
    // so a caller can continue only this draft, never a subsequently selected save.
    const ownsStartedDraft=requestIdentity(sessionStore,request.owned)
    if(r.worldPending || r.status==='WORLD_PENDING') {loadingStart.value=false;return ownsStartedDraft}
    if(!r.worldPending && !worldSetting.value?.introduction)worldReviewed.value=true
    if (questions.value.length === 0 && r.status!=='WORLD_PENDING') {
      ElMessage.warning('后端未返回问题，请检查服务')
    }
    return ownsStartedDraft
  } catch (e) {
    if(request.valid())handleErr(e, '加载三问失败')
  } finally {
    if(request.owned())loadingStart.value = false
  }
}

async function loadWorld(){
  if(loadingWorld.value || !sessionStore.sessionId)return
  const request=beginCreationRequest();loadingWorld.value=true;worldError.value=''
  try{
    const r=await http.post('/game/world',{sessionId:sessionStore.sessionId,playerId:sessionStore.playerId,worldTheme:worldTheme.value})
    if(!request.valid())return
    worldSetting.value=r.worldSetting;questions.value=r.questions || []
    sessionStore.setActiveSession({archiveReady:true})
  }catch(e){if(request.valid())worldError.value=e.response?.data?.message || '世界暂未生成完成，可重试；尚未建立新存档。'}
  finally{if(request.owned())loadingWorld.value=false}
}
async function beginWorld(){
 if(loadingWorld.value || loadingStart.value)return
 if(!sessionStore.sessionId){const ownsStartedDraft=await loadStart();if(!ownsStartedDraft?.())return}
 if(viewAlive && sessionStore.sessionId && !worldSetting.value?.introduction)await loadWorld()
}

async function submitAnswers() {
  if(loadingStart.value || loadingWorld.value || !worldReviewed.value || loadingAnswer.value || loadingSelect.value) return
  if(talents.value.length){step.value=1;return}
  if (answers.value.some(a => !a || !a.trim())) {
    ElMessage.warning('请回答所有三问')
    return
  }
  const request=beginCreationRequest()
  loadingAnswer.value = true
  try {
    const r = await http.post('/game/answer', {
      sessionId: sessionStore.sessionId,
      playerId: sessionStore.playerId,
      answer1: answers.value[0],
      answer2: answers.value[1],
      answer3: answers.value[2]
    })
    if(!request.valid())return
    personalityTags.value = r.personalityTags || []
    talents.value = r.talents || []
    if (talents.value.length === 0) {
      ElMessage.warning('天赋生成失败，请重试')
      return
    }
    selectedTalentId.value = null
    step.value = 1
  } catch (e) {
    if(request.valid())handleErr(e, '呈递答案失败')
  } finally {
    if(request.owned())loadingAnswer.value = false
  }
}

async function selectTalent() {
  if(loadingSelect.value || loadingAnswer.value) return
  if (!selectedTalentId.value) {
    ElMessage.warning('请选择一个天赋')
    return
  }
  const request=beginCreationRequest()
  loadingSelect.value = true
  try {
    const r = await http.post('/game/talent/select', {
      sessionId: sessionStore.sessionId,
      playerId: sessionStore.playerId,
      talentId: selectedTalentId.value
    })
    if(!request.valid())return
    applyBirth(r)
  } catch (e) {
    if(request.valid())handleErr(e, '入世失败')
  } finally {
    if(request.owned())loadingSelect.value = false
  }
}

function applyBirth(r) {
    birthStory.value = r.birthStory || ''
    initialLocation.value = r.initialLocation || null
    storyTime.value = r.storyTime || ''
    sceneId.value = r.sceneId || null
    playerGodId.value = r.playerGodId || null

    // 写入 SessionStore，标记为可玩状态
    sessionStore.setActiveSession({
      sceneId: sceneId.value,
      playerGodId: playerGodId.value,
      status: r.sessionStatus || 'PLAYING'
    })
    if(r.objectives)sessionStore.setObjectives(r.objectives)
    step.value = 2
}

function enterGame() {
  router.push('/play')
}

onMounted(()=>{document.title='此生行记 · 旅程之始';loadStart()})
</script>

<style scoped>
.world-theme-options{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;padding:0;margin:24px 0;border:0;max-width:580px}
.world-theme-options legend{font:11px/1.8 system-ui;color:#6e8294;margin-bottom:10px;letter-spacing:2px}
.world-theme-options label{position:relative;display:flex;flex-direction:column;gap:8px;padding:19px 13px;border:1px solid #bac8d3;background:#ffffff80;cursor:pointer;color:#536f86;transition:background .16s,border-color .16s}
.world-theme-options label.selected{background:#e7edf1;border-color:#476d92;box-shadow:inset 0 -2px #476d92}
.world-theme-options input{position:absolute;right:10px;top:12px;accent-color:#365e83}
.world-theme-options strong{font:21px/1.6 SimSun,serif;letter-spacing:2px}
.world-theme-options small{font:10px/1.8 system-ui;color:#73899b}
.world-theme-options label:focus-within{outline:2px solid #527594;outline-offset:3px}
.world-theme-options:disabled label{cursor:wait;opacity:.65}
@media(max-width:760px){.world-theme-options{gap:7px}.world-theme-options label{padding:17px 9px}.world-theme-options strong{font-size:19px}.world-theme-options small{font-size:9px}}
.talent-selected {
  border: 2px solid var(--myth-bronze) !important;
  background: var(--myth-bg-alt) !important;
  box-shadow: 0 0 0 2px rgba(107, 91, 60, 0.15);
}
</style>
