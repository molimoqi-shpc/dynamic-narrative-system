<template>
  <main class="entry-page entry-cabinet">
    <div class="entry-art" aria-hidden="true" /><div class="entry-veil" aria-hidden="true" /><AmbientParticles />
    <header class="entry-header"><a href="/login" class="entry-brand"><CompassMark /><span>此生行记<small>故事未尽 · 众生同席</small></span></a><span class="entry-header-note">各有来路，各有所求。</span></header>
    <div class="entry-layout">
      <div class="entry-diagonal-art" aria-hidden="true"><img src="/art/utility-engravings-v1/threshold-labyrinth.webp" alt=""/><EtchedOrnament kind="threshold"/></div><section class="entry-intro" aria-label="故事世界入口">
        <p class="entry-kicker"><span /> STORIES STILL UNWRITTEN</p>
        <h1><CelestialTitle text="走入世界，" /><br><CelestialTitle text="写下此生。" /></h1><FlightMark class="entry-flight"/>
        <p class="entry-description">一个世界，许多人的一生。<br>走入其中，留下你的选择。</p>
        <div class="entry-verse"><span aria-hidden="true">☽</span><p>一念入世，万象有声。<br><small>你的旅程，从此刻开始。</small></p></div>
      </section>
      <section class="entry-panel" aria-label="账号入口">
        <EtchedOrnament kind="threshold" class="entry-panel-etch"/><div class="entry-panel-halo" aria-hidden="true" /><p class="entry-panel-kicker">THE THRESHOLD</p>
        <nav v-if="!reconnecting" class="entry-tabs" aria-label="选择登录或注册"><button type="button" :aria-pressed="!registering" :disabled="loading" @click="changeMode(false)">入梦 · 登录</button><button type="button" :aria-pressed="registering" :disabled="loading" @click="changeMode(true)">初见 · 注册</button></nav>
        <h2>{{reconnecting?'重新连接旅程':registering?'为此身，留一个名字':'翻开未竟的故事'}}</h2><p class="entry-form-note">{{reconnecting?'连接恢复后，继续读取原有进展。':registering?'创建账号后，自行开启一段新的旅程。':'登录账号，翻开未竟的命途。'}}</p>
        <div v-if="reconnecting" class="entry-form">
          <p class="entry-feedback" role="status">暂时无法连接服务器，登录信息与所选存档已保留。可重试连接，无需重新生成故事。</p>
          <p v-if="error" class="entry-feedback error" role="alert">{{error}}</p>
          <button class="entry-submit" type="button" :disabled="loading" @click="reconnect"><span v-if="loading" class="entry-spinner" aria-hidden="true" />{{loading?'正在重新连接…':'重试连接'}}<span v-if="!loading" aria-hidden="true">↗</span></button>
          <div class="entry-demo"><button type="button" :disabled="loading" @click="router.replace('/login')">使用账号密码登录</button></div>
        </div>
        <form v-else class="entry-form" @submit.prevent="submit" novalidate>
          <label for="entry-username">账号<span>{{registering?'3—30位字母、数字或下划线':''}}</span></label>
          <div class="entry-field"><span aria-hidden="true">◇</span><input id="entry-username" aria-label="账号" ref="usernameField" v-model="form.username" name="username" autocomplete="username" maxlength="50" :disabled="loading" placeholder="你的账号" :aria-invalid="!!error || undefined" /></div>
          <label for="entry-password">密码<span v-if="registering">至少8位</span></label>
          <div class="entry-field"><span aria-hidden="true">⌑</span><input id="entry-password" aria-label="密码" v-model="form.password" name="password" :type="showPassword?'text':'password'" :autocomplete="registering?'new-password':'current-password'" maxlength="72" :disabled="loading" placeholder="你的密码" /><button type="button" :disabled="loading" :aria-label="showPassword?'隐藏密码':'显示密码'" :aria-pressed="showPassword" @click="showPassword=!showPassword">{{showPassword?'隐藏':'显示'}}</button></div>
          <template v-if="registering"><label for="entry-confirm">确认密码</label><div class="entry-field"><span aria-hidden="true">⌑</span><input id="entry-confirm" v-model="form.confirm" name="confirmPassword" type="password" autocomplete="new-password" maxlength="72" :disabled="loading" placeholder="再输入一次密码" /></div></template>
          <p v-if="error" class="entry-feedback error" role="alert">{{error}}</p><p v-if="notice" class="entry-feedback success" role="status">{{notice}}</p>
          <button class="entry-submit" type="submit" :disabled="loading"><span v-if="loading" class="entry-spinner" aria-hidden="true" />{{loading?(registering?'正在创建账号…':'正在开启入梦之门…'):(registering?'创建账号':'进入旅程')}}<span v-if="!loading" aria-hidden="true">↗</span></button>
        </form>
        <details v-if="!registering && !reconnecting" class="entry-demo"><summary>先体验这方天地 <span aria-hidden="true">＋</span></summary><p>演示账号 player_demo · 密码 123456</p><button type="button" :disabled="loading" @click="useDemo">填入演示账号</button></details>
        <p class="entry-panel-foot">知 · 慧 · 斗 · 闻 · 本 · 归</p>
      </section>
    </div>
    <footer class="entry-footer"><span>此生行记</span><span>世界未尽，故事仍在继续。</span><span class="entry-footer-index">Ⅰ / 入世之门</span></footer>
  </main>
</template>
<script setup>
import FlightMark from '../components/FlightMark.vue'
import EtchedOrnament from '../components/EtchedOrnament.vue'
import CompassMark from '../components/CompassMark.vue'
import { reactive, ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '../api'
import { useSessionStore } from '../store/session'
import {requestIdentity} from '../utils/requestIdentity'
import AmbientParticles from '../components/AmbientParticles.vue'
import CelestialTitle from '../components/CelestialTitle.vue'
onMounted(()=>{document.body.classList.add('entry-screen');document.title='此生行记 · 账号'})
let alive=true
onBeforeUnmount(()=>{alive=false;document.body.classList.remove('entry-screen')})
const router=useRouter(),route=useRoute(),session=useSessionStore()
const reconnecting=computed(()=>route.query.reconnect==='1' && !!localStorage.getItem('bs_token'))
const registering=ref(false),loading=ref(false),showPassword=ref(false),error=ref(''),notice=ref(''),usernameField=ref(null)
const form=reactive({username:'',password:'',confirm:''})
function changeMode(mode){if(loading.value)return;registering.value=mode;form.password='';form.confirm='';error.value='';notice.value='';showPassword.value=false}
function useDemo(){form.username='player_demo';form.password='123456';error.value='';notice.value='演示账号已填入，点击进入旅程。'}
async function reconnect(){
 if(loading.value)return
 const requested=route.query.returnTo
 // Only return to an existing authenticated app route, never an external URL
 // supplied through a query string or a second login/reconnect page.
 const destination=typeof requested==='string' && requested.startsWith('/') && !requested.startsWith('//')?router.resolve(requested):null
 const path=destination?.matched.some(record=>record.meta.requiresAuth)?destination.fullPath:'/home'
 loading.value=true;error.value=''
 try{await router.replace(path);if(alive && route.query.reconnect==='1')error.value='连接尚未恢复，请稍后重试。'}
 catch{if(alive)error.value='连接尚未恢复，请稍后重试。'}
 finally{if(alive)loading.value=false}
}
async function submit(event){
 if(loading.value)return
 if(event?.currentTarget?.tagName==='FORM'){
  // Native autofill may paint fields without dispatching Vue input events.
  const fields=new FormData(event.currentTarget)
  form.username=String(fields.get('username') || '')
  form.password=String(fields.get('password') || '')
  if(registering.value)form.confirm=String(fields.get('confirmPassword') || '')
 }
 error.value='';notice.value=''
 const username=form.username.trim()
 if(!username || !form.password){error.value='请填写账号与密码。';return}
 if(registering.value){
  if(!/^[A-Za-z0-9_]{3,30}$/.test(username)){error.value='账号需为3至30位字母、数字或下划线。';return}
  if(form.password.length<8 || new TextEncoder().encode(form.password).length>72){error.value='密码至少8位；若密码过长，请减少字符（纯中文最多24字）。';return}
  if(form.password!==form.confirm){error.value='两次输入的密码不一致。';return}
 }
 const token=localStorage.getItem('bs_token'),identity=requestIdentity(session,()=>alive)
 const owned=()=>identity.owned() && token===localStorage.getItem('bs_token')
 loading.value=true
 try{
  if(registering.value){await http.post('/auth/register',{username,password:form.password});if(!owned())return;changeAfterRegister(username);loading.value=false;await nextTick();if(alive)usernameField.value?.focus();return}
  const account=await http.post('/auth/login',{username,password:form.password})
  if(!owned())return
  if(!account.token || !account.playerId)throw new Error('登录信息不完整，请重试。')
  localStorage.setItem('bs_token',account.token);session.clearSession();session.setActiveSession({playerId:account.playerId});form.password='';await router.replace('/home')
 }catch(e){if(owned())error.value=e.response?.data?.message || e.message || '暂时无法连接，请重试。'}finally{if(alive)loading.value=false}
}
function changeAfterRegister(username){registering.value=false;form.username=username;form.password='';form.confirm='';showPassword.value=false;notice.value='账号已创建。请登录，开启你的旅程。'}
</script>
