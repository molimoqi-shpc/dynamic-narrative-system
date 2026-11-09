<template>
  <main class="home-page folio-home" :data-launch-kind="launchState.kind || undefined" :aria-busy="transitionState.busy || preparing">
    <AmbientParticles />
    <nav class="home-nav"><div class="brand-lockup"><CompassMark /><span>此生行记<small>互动叙事 · 此生行记</small></span></div><div class="home-account"><span class="online-dot" />旅人已入梦<el-button text @click="logout">退出登录</el-button></div></nav>
    <button type="button" class="home-portrait-entry" :disabled="transitionState.busy || preparing" @click="openPortraitLibrary" aria-haspopup="dialog" :aria-expanded="portraitLibraryOpen"><TheatreGlyph name="person"/><span>图鉴</span><small>众生留影</small></button>
    <section class="home-hero">
      <div class="hero-engraving" aria-hidden="true"><CompassMark/><FlightMark/></div>
      <div class="hero-copy"><p class="eyebrow">此生行记 / 序</p><h1><CelestialTitle text="一念入梦，" /><br><CelestialTitle text="万象成书。" /></h1><p class="hero-description">走入一个新的世界，与各有来路的人相逢。<br>故事从你的选择开始，向未知的地方延伸。</p><div class="hero-attributes"><span class="stat-aura" v-for="label in ['知','慧','斗','闻','本','归']" :key="label">{{ label }}</span><small>六维相生 · 命途自定</small></div></div>
      <div class="home-vignette" aria-hidden="true"><div class="vignette-scenery"/><span class="threshold-slice"/><img class="threshold-person" src="/art/refraction-v2/western-elf-female.png" alt="" /><svg class="vignette-orbit" viewBox="0 0 700 500"><path d="M710 40C390 10 300 210 390 420M690 80C430 45 355 240 440 400"/></svg><span class="vignette-index">01 / 未写之境</span><span class="vignette-caption">相逢、选择，以及后来。</span></div>
    </section>
    <section class="journey-section">
      <div class="section-caption"><span class="section-invitation"><FlightMark/>启卷 · 开始你的旅程</span><div class="view-choice"><button :class="{active:viewMode.mode==='DREAMER'}" @click="viewMode.setMode('DREAMER')" :aria-pressed="viewMode.mode==='DREAMER'">入梦 · 身临其境</button><button :class="{active:viewMode.mode==='OMNISCIENT'}" @click="viewMode.setMode('OMNISCIENT')" :aria-pressed="viewMode.mode==='OMNISCIENT'">天眼 · 洞察真相</button></div></div>
      <div class="journey-grid">
        <button class="journey-card journey-primary" :disabled="transitionState.busy || preparing" :class="{'is-launching':launchState.kind==='angel'}" @click="enter('angel',goNewGame,$event)"><MenuMotif kind="angel"/><span class="journey-symbol">启</span><div class="card-copy"><span class="card-kicker">新的命途</span><h2>新开一局</h2><p>先认识世界，再写下自己的来路。</p></div><span class="card-arrow">↗</span></button>
        <button class="journey-card" :disabled="transitionState.busy || preparing" :class="{'is-launching':launchState.kind==='gears'}" @click="enter('gears',showSessions,$event)"><MenuMotif kind="gears"/><span class="journey-symbol">续</span><div class="card-copy"><span class="card-kicker">重逢旧梦</span><h2>继续游戏</h2><p>翻开旧卷，续写未竟的故事。</p></div><span class="card-arrow">↗</span></button>
        <button class="journey-card" :disabled="transitionState.busy || preparing" :class="{'is-launching':launchState.kind==='tide'}" @click="enter('tide',goEngineDemo,$event)"><MenuMotif kind="tide"/><span class="journey-symbol">观</span><div class="card-copy"><span class="card-kicker">六维演算</span><h2>引擎演示</h2><p>了解六维数值与对抗计算。</p></div><span class="card-arrow">↗</span></button>
        <button class="journey-card" :disabled="transitionState.busy || preparing" :class="{'is-launching':launchState.kind==='hand'}" @click="enter('hand',()=>router.push('/about'),$event)"><MenuMotif kind="hand"/><span class="journey-symbol">记</span><div class="card-copy"><span class="card-kicker">创作札记</span><h2>关于本作</h2><p>认识这方天地与它的规则。</p></div><span class="card-arrow">+</span></button>
      </div>
    </section>
    <footer class="home-footer"><button class="home-music-entry" @click="openHomeMusic" aria-label="展开背景音乐播放台"><span aria-hidden="true">♫</span> 留声</button><span>此生行记 · 众生有声</span><FlightMark/><span>世界未尽，故事仍在继续。</span></footer>
    <el-drawer v-model="drawerVisible" title="旧梦 · 存档" class="session-library-drawer engraved-archive" direction="rtl" size="min(800px, 98vw)">
      <div v-loading="loadingSessions" class="session-library" data-menu-destination="gears"><header class="archive-frontispiece" data-arrival="title"><EtchedOrnament kind="time"/><div><p class="library-kicker">ARCHIVE / 旧卷重续</p><h2>旧梦有迹。</h2><p class="library-description">每一份留存，都有自己的世界与来路。</p></div></header><div class="archive-directory-line" data-arrival="body"><span>此生留存</span><small>{{sessions.length}} 卷</small></div><div class="archive-card-grid">
        <el-empty v-if="!loadingSessions && sessions.length===0" description="尚无旧梦，开启第一段旅程吧" />
        <div v-paper-light class="save-card tactile-surface" data-arrival="item" :class="{'save-resuming':resuming===s.sessionId}" :aria-busy="resuming===s.sessionId" v-for="(s,index) in sessions" :key="s.sessionId"><PaperFinish/>
          <EtchedOrnament kind="time" class="save-card-etch"/><div class="save-card-heading"><span class="save-number">{{ String(index+1).padStart(2,'0') }}</span><el-tag size="small">{{ statusText[s.status] || s.status }}</el-tag></div>
          <h3>{{s.worldTitle || (s.status==='CREATING'?'待开启的命途':'未竟的旅程')}}</h3><p v-if="s.showcase" class="save-where">展示存档 · {{s.chapterTitle || '故事进行中'}}</p><p v-if="s.playerName || s.locationName" class="save-where">{{[s.playerName,s.locationName].filter(Boolean).join(' · ')}}</p><p>{{formatDate(s.createdAt)}} · {{s.viewMode==='OMNISCIENT'?'天眼视角':'入梦视角'}}</p>
          <el-button type="primary" :loading="resuming===s.sessionId" :disabled="!!resuming || !['CREATING','PLAYING','DEAD','RETIRED','VICTORY'].includes(s.status)" @click="resumeSession(s)">{{s.status==='CREATING'?'继续化生':'继续旅程'}}</el-button>
          <StoryExportButton :session-id="s.sessionId" :disabled="s.status==='CREATING' || !!resuming" />
        </div></div>
      </div>
    </el-drawer>
    <PortraitLibrary v-model:open="portraitLibraryOpen"/>
  </main>
</template>
<script setup>
import PaperFinish from '../components/PaperFinish.vue'
import StoryExportButton from '../components/StoryExportButton.vue'
import PortraitLibrary from '../components/PortraitLibrary.vue'
import TheatreGlyph from '../components/TheatreGlyph.vue'
import {paperLight as vPaperLight} from '../utils/paperLight'
import AmbientParticles from '../components/AmbientParticles.vue'
import CelestialTitle from '../components/CelestialTitle.vue'
import { ref,watch,onMounted,onBeforeUnmount,computed } from 'vue'
import MenuMotif from '../components/MenuMotif.vue'
import {menuTransition,menuSceneAssets,prepareMenuAsset} from '../utils/menuTransition'
import {createMenuLauncher} from '../utils/menuEntry'
import {currentMotion} from '../utils/motionPreferences'
import CompassMark from '../components/CompassMark.vue'
import FlightMark from '../components/FlightMark.vue'
import EtchedOrnament from '../components/EtchedOrnament.vue'
import { useViewModeStore } from '../store/viewMode'
import { useRouter,useRoute } from 'vue-router'
import { ElMessage, ElButton, ElDrawer, ElEmpty, ElTag } from 'element-plus'
import http from '../api'
import { useSessionStore } from '../store/session'
import {requestIdentity} from '../utils/requestIdentity'

onMounted(()=>{document.title='此生行记 · 旅程';if(currentMotion()==='FULL')Object.values(menuSceneAssets).flat().forEach(src=>void prepareMenuAsset(src))})
const transitionState=menuTransition.state,launcher=createMenuLauncher(),launchState=launcher.state
const preparing=computed(()=>launchState.preparing)
const portraitLibraryOpen=ref(false)
function openPortraitLibrary(){window.dispatchEvent(new Event('close-music-panel'));portraitLibraryOpen.value=true}
onBeforeUnmount(launcher.dispose)
async function enter(kind,action,event){
 try{
  await launcher.enter(kind,action,event?.currentTarget)
 }catch(e){ElMessage.error(e.message || '页面暂未打开，请重试')}
}
function showSessions(){return openSessions()}
function openHomeMusic(){window.dispatchEvent(new Event('open-music-panel'))}
const router = useRouter(),route=useRoute()
const viewMode = useViewModeStore()
const resuming = ref(null)
const statusText = {CREATING:"待入世",PLAYING:"旅程中",DEAD:"已陨落",RETIRED:"归隐终章",VICTORY:"旧版通关"}
function formatDate(value) { const date=new Date(value); return Number.isNaN(date.getTime()) ? "时间未记录" : date.toLocaleString("zh-CN",{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"}) }
const sessionStore = useSessionStore()

const drawerVisible = ref(false)
const loadingSessions = ref(false)
const sessions = ref([])
let homeAlive=true,sessionListSequence=0,resumeSequence=0
onBeforeUnmount(()=>{homeAlive=false;sessionListSequence++;resumeSequence++})
watch(()=>route.query.archives,value=>{if(value==='1')openSessions()},{immediate:true})

function goNewGame() {
  const accountId=sessionStore.playerId
  sessionStore.clearSession()
  sessionStore.setActiveSession({playerId:accountId})
  return router.push('/new-game')
}
function goEngineDemo() {
  return router.push('/engine-demo')
}

async function openSessions() {
  window.dispatchEvent(new Event('close-music-panel'))
  drawerVisible.value = true
  if (!sessionStore.playerId) {
    ElMessage.warning('请先登录')
    return
  }
  await loadSessions()
}

async function loadSessions() {
  const sequence=++sessionListSequence
  const valid=requestIdentity(sessionStore,()=>homeAlive && sequence===sessionListSequence)
  loadingSessions.value = true
  try {
    const r = await http.get('/game/sessions', { params: { playerId: sessionStore.playerId } })
    if(!valid())return
    sessions.value = r.sessions || []
  } catch (e) {
    if(valid())ElMessage.error(e.response?.data?.message || e.message || '加载存档失败')
  } finally {
    if(homeAlive && sequence===sessionListSequence)loadingSessions.value = false
  }
}

async function resumeSession(s) {
  if (resuming.value) return
  resuming.value=s.sessionId
  const sequence=++resumeSequence
  const valid=requestIdentity(sessionStore,()=>homeAlive && sequence===resumeSequence)
  const accountId=sessionStore.playerId
  try {
    if(s.status==="CREATING") {
      await http.get("/game/creation",{params:{sessionId:s.sessionId,playerId:sessionStore.playerId}})
      if(!valid())return
      sessionStore.clearSession()
      sessionStore.setActiveSession({playerId:accountId,sessionId:s.sessionId,status:"CREATING"})
      viewMode.setMode(s.viewMode || "DREAMER")
      await router.push("/new-game")
      return
    }
    const restored=await http.post('/game/resume',{sessionId:s.sessionId,playerId:sessionStore.playerId})
    if(!valid())return
    sessionStore.clearSession()
    sessionStore.setActiveSession({...restored,playerId:accountId})
    viewMode.setMode(s.viewMode || "DREAMER")
    drawerVisible.value=false
    router.push('/play')
  } catch(e) { if(valid())ElMessage.error(e.response?.data?.message || '恢复会话失败') }
  finally {if(homeAlive && sequence===resumeSequence)resuming.value=null}
}

async function logout() {
  const token=localStorage.getItem('bs_token'),accountId=sessionStore.playerId
  try {await http.post('/auth/logout',null,{timeout:10000})} catch(e) { /* Local sign-out remains available if the server is unreachable. */ }
  if(token!==localStorage.getItem('bs_token') || accountId!==sessionStore.playerId)return
  localStorage.removeItem('bs_token')
  sessionStore.clearSession()
  router.push('/login')
}


</script>
<style scoped>
.home-portrait-entry{position:fixed;right:16px;top:43%;z-index:20;display:grid;grid-template-columns:23px auto;align-items:center;gap:2px 8px;padding:12px 15px;background:#eef2eeeF;border:1px solid #a8b9c5;border-right:3px solid #456c89;color:#355c78;box-shadow:0 8px 22px #203c4a12;cursor:pointer;transition:transform .2s,box-shadow .2s;font-family:inherit}.home-portrait-entry .theatre-glyph{width:23px;height:27px;grid-row:1/3}.home-portrait-entry>span{font-size:13px;letter-spacing:3px}.home-portrait-entry small{font-size:8px;color:#81939f;letter-spacing:1px}.home-portrait-entry:hover{transform:translateX(-3px);box-shadow:0 10px 25px #203c4a23}.home-portrait-entry:focus-visible{outline:2px solid #426e95;outline-offset:4px}.home-portrait-entry:disabled{opacity:.5;cursor:default;transform:none}@media(max-width:650px){.home-portrait-entry{top:auto;right:14px;bottom:85px;padding:10px 12px}}@media(prefers-reduced-motion:reduce){.home-portrait-entry{transition:none}}
</style>
