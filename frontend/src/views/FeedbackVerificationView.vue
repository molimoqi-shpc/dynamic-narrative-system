<template>
 <main class="feedback-verification">
  <header><div><small>游戏反馈 · 本地验收</small><h1>每一步，都有实际经过</h1></div><router-link to="/play">打开游戏 →</router-link></header>
  <p class="verification-note">下方使用后端集成测试实际返回的记录，模型为受控 mock。时间线与人物面板复用游戏主页面的 Vue 组件；切换记录不会执行游戏行动。</p>
  <p v-if="error" role="alert">{{error}}</p>
  <nav aria-label="选择演练"><button v-for="(item,index) in cases" :key="item.scenario" :aria-pressed="index===selected" @click="selected=index">{{item.scenario}}</button></nav>
  <div v-if="current" class="verification-layout">
   <article><div class="verification-chapter-label">亲历故事 / {{current.scene?.locationName}}</div><h2>{{current.scenario}}</h2><p class="verification-input"><span>你的决定</span>{{current.playerInput}}</p><div class="verification-story">{{(current.lines || []).map(line=>line.speech).join('\n\n')}}</div><GameChapterProgress :chapter="chapter" /><p class="verification-explanation">{{current.explanation}}</p></article>
   <aside><GameSceneRail :scene="current.scene || {}" :atmosphere="sceneAtmosphere(current.scene)" :busy="true" :objectives="current.scene?.objectives || []" /></aside>
  </div>
 </main>
</template>
<script setup>
import {computed,onMounted,ref} from 'vue'
import GameChapterProgress from '../components/GameChapterProgress.vue'
import GameSceneRail from '../components/GameSceneRail.vue'
import {mergeOperationChapter} from '../utils/gameOperation.js'
import {sceneAtmosphere} from '../utils/sceneAtmosphere.js'
const cases=ref([]),selected=ref(0),error=ref('')
const current=computed(()=>cases.value[selected.value])
const chapter=computed(()=>current.value?mergeOperationChapter([],{...current.value,logId:1},current.value.playerInput)[0]:{})
onMounted(async()=>{try{const response=await fetch('/verification-cases.json');if(!response.ok)throw new Error('未能读取验收记录');const data=await response.json();const order=['条件满足后立即行动','并行行动','拒借','亲见离场不揭露目的地','未感知离场保留最近所见','按旧所见呼唤也消耗真实时间','实际观察后更新人物面板'];cases.value=order.map(name=>data.find(item=>item.scenario===name)).filter(Boolean)}catch(e){error.value=e.message}})
</script>
<style scoped>
.feedback-verification{--myth-ink:#e5e8e4;--myth-bronze:#cabc9b;min-height:100vh;background:#131f29;color:var(--myth-ink);padding:32px clamp(20px,5vw,72px);font:14px/1.8 system-ui,'Microsoft YaHei',sans-serif}.feedback-verification>header{display:flex;align-items:center;justify-content:space-between;gap:16px;max-width:1200px;margin:auto}.feedback-verification header small{color:#cabc9b;letter-spacing:3px;font-size:10px}.feedback-verification h1{color:#e4d6b3;font-size:30px;font-weight:400;margin:8px 0}.feedback-verification a{color:#cabc9b;text-decoration:none;white-space:nowrap}.verification-note{max-width:1200px;margin:12px auto 24px;color:#9fadb7;border-left:2px solid #67777f;padding-left:14px;font-size:12px}.feedback-verification nav{display:flex;flex-wrap:wrap;gap:8px;max-width:1200px;margin:0 auto 24px}.feedback-verification nav button{background:transparent;color:#acb9c4;border:1px solid #384651;padding:8px 13px;border-radius:5px;font:inherit;font-size:12px;cursor:pointer}.feedback-verification nav button[aria-pressed=true]{border-color:#a39372;background:#a393721c;color:#e4d6b3}.verification-layout{display:grid;grid-template-columns:minmax(0,1fr) 285px;gap:24px;max-width:1200px;margin:auto}.verification-layout article{padding:32px clamp(20px,4vw,56px);background:#192936;border:1px solid #344451;border-radius:8px}.verification-chapter-label{color:#9baeba;letter-spacing:3px;font-size:10px}.verification-layout h2{color:#e4d6b3;font-size:24px;font-weight:400;line-height:1.5;margin:14px 0 24px}.verification-input{border-left:1px solid #a39372;padding-left:13px;font-size:12px;color:#a6b6c4;margin-bottom:28px}.verification-input span{color:#d1bf98;margin-right:14px}.verification-story{font-family:Georgia,'Microsoft YaHei',serif;white-space:pre-wrap;line-height:2.1;font-size:17px;min-height:110px}.verification-explanation{margin-top:28px;border-top:1px solid #344451;padding-top:16px;color:#a6b6c4;font-size:12px}.verification-layout aside{padding:0 22px 24px;border:1px solid #344451;border-radius:8px;background:#172633;overflow:hidden}.verification-layout :deep(.chapter-wait-met){color:#acd1b9;background:#729f7a1a}.verification-layout :deep(.chapter-wait p){flex-basis:100%;margin:0 0 0 22px;font-size:12px}.verification-layout :deep(.rail-self-body){display:flex;gap:14px;color:#d2c6a8;font-size:12px}.verification-layout :deep(.rail-people button:disabled){opacity:1;cursor:default}.verification-layout :deep(.rail-people small){white-space:normal;line-height:1.5}.verification-layout :deep(.rail-people button>span:nth-child(2)){min-width:0}@media(max-width:760px){.verification-layout{grid-template-columns:1fr}.feedback-verification{padding:22px 16px}.feedback-verification h1{font-size:24px}.verification-layout aside{padding-top:15px}.verification-story{font-size:16px}}
</style>

