<template>
 <Teleport to="body">
  <div v-if="state.active" ref="host" class="menu-transition" :class="[`effect-${state.kind}`,`phase-${state.phase}`,{'motion-reduced':state.reduced,'has-origin':!!state.origin}]" :style="{'--enter-time':menuTimings[state.kind]+'ms','--reveal-time':menuRevealTimings[state.kind]+'ms'}" role="dialog" aria-modal="true" :aria-label="labels[state.kind]" tabindex="-1">
   <div v-if="state.origin && !state.reduced" class="menu-origin-trace" :class="`origin-${state.kind}`" :style="{'--origin-x':state.origin.x+'px','--origin-y':state.origin.y+'px','--origin-scale':state.origin.scale}" aria-hidden="true"><span class="origin-ring"/><MenuMotif :kind="state.kind"/></div>
   <div class="transition-veil" aria-hidden="true" />
   <MenuTransitionScenery :kind="state.kind" />
   <div v-if="state.kind==='angel'" class="angel-scene" aria-hidden="true">
    <SeraphEntrance v-if="!state.reduced"/>
    <span class="angel-rays"/><span class="angel-glow" /><p class="transition-caption"><small>序章 / 一段未写的来路</small>启卷 · 新的命途</p>
   </div>
   <div v-else-if="state.kind==='gears'" class="gear-scene" aria-hidden="true">
    <div class="gear-door door-left"><span class="door-engraving"/></div><div class="gear-door door-right"><span class="door-engraving"/></div>
    <div v-for="side in ['left','right']" :key="side" class="gear-rig" :class="`rig-${side}`"><GoldenTether :side="side"/><div class="gear-half" :class="`gear-${side}`"><svg viewBox="0 0 400 400"><defs><linearGradient :id="`gear-metal-${side}`" x2="1" y2="1"><stop stop-color="#f7e9bc"/><stop offset=".25" stop-color="#a88142"/><stop offset=".49" stop-color="#edce83"/><stop offset=".52" stop-color="#78552d"/><stop offset=".78" stop-color="#cba75e"/><stop offset="1" stop-color="#f0dba0"/></linearGradient></defs><g class="gear-turn" :fill="`url(#gear-metal-${side})`" stroke="#edd39a" stroke-width=".7"><path :d="gearPath" fill-rule="evenodd"/><circle cx="200" cy="200" r="159" fill="none" stroke="#493925"/><circle cx="200" cy="200" r="151" fill="none" stroke-dasharray="2 6"/><circle cx="200" cy="200" r="123" fill="none"/><path v-for="n in 8" :key="n" d="M194 93H206L211 155H189Z" :transform="`rotate(${n*45} 200 200)`"/></g><g class="gear-inner" fill="none" stroke="#d5b778"><circle cx="200" cy="200" r="105"/><circle cx="200" cy="200" r="96" stroke-dasharray="16 5 2 5"/><path v-for="n in 12" :key="n" d="M198 112h4v12h-4z" :transform="`rotate(${n*30} 200 200)`"/></g><circle cx="200" cy="200" r="43" fill="#172431" stroke="#d3b776"/><circle cx="200" cy="200" r="35" fill="none" stroke="#d3b776" stroke-width=".5"/><path class="gear-lock-pin" d="M194 185h12v30h-12z" fill="#f0d698"/></svg><span class="gear-specular"/></div></div>
    <span class="gear-unlock"/><p class="transition-caption"><small>旧卷 / 尚未走完的旅程</small>往事有迹 · 重续此生</p>
   </div>
   <div v-else-if="state.kind==='tide'" class="tide-scene" aria-hidden="true">
    <div class="tide-rising"><svg class="tide-cap" viewBox="0 0 1440 180" preserveAspectRatio="none"><path class="tide-far" d="M0 75C180 155 285 0 480 48S760 120 960 41 1220 112 1440 18V180H0Z"/><path class="tide-crimson" d="M0 90C160 150 260 0 440 67S700 145 880 63 1170 118 1440 42V180H0Z"/><path class="tide-black" d="M0 121C160 170 260 36 440 98S700 179 880 96 1170 155 1440 74V180H0Z"/><path class="tide-rim" d="M0 121C160 170 260 36 440 98S700 179 880 96 1170 155 1440 74"/></svg><div class="tide-body"><HauntedLake v-if="!state.reduced"/><svg class="tide-currents" viewBox="0 0 1440 900" preserveAspectRatio="none" fill="none"><path v-for="n in 9" :key="n" :d="`M-100 ${n*100}C200 ${-100+n*100} 520 ${280+n*60} 820 ${n*75}S1200 ${-120+n*100} 1540 ${n*110}`"/></svg><span class="tide-eddy eddy-one"/><span class="tide-eddy eddy-two"/></div></div>
    <p class="transition-caption"><small>知 · 慧 · 斗 · 闻 · 本 · 归</small>潮起 · 万象相生</p>
   </div>
   <div v-else class="hand-scene" aria-hidden="true">
    <RainSceneLeaf v-if="!state.reduced"/>
   </div>
   <p v-if="state.reduced" class="transition-short-label" role="status">{{state.phase==='covered'?'正在打开这一页…':'切换此间'}}<small>已启用简洁转场</small></p><button class="transition-skip" @click="menuTransition.skip()">跳过动画 <span>Esc</span></button>
  </div>
 </Teleport>
</template>
<script setup>
import {ref,watch,nextTick,onMounted,onBeforeUnmount} from 'vue'
import {menuTransition,menuTimings,menuRevealTimings} from '../utils/menuTransition'
import MenuTransitionScenery from './MenuTransitionScenery.vue'
import SeraphEntrance from './SeraphEntrance.vue'
import GoldenTether from './GoldenTether.vue'
import HauntedLake from './HauntedLake.vue'
import RainSceneLeaf from './RainSceneLeaf.vue'
import MenuMotif from './MenuMotif.vue'
import {playMenuArrival} from '../utils/menuArrival'
const {state}=menuTransition,host=ref(null)
const labels={angel:'天使展翼，开启新的旅程',gears:'齿轮解锁，打开存档',tide:'黑红潮起，进入引擎演示',hand:'雨中翻页，打开创作札记'}
// Native vector gear: a crisp scalable annulus with 28 machined teeth.
const gearPath=Array.from({length:112},(_,i)=>{const radius=[174,187,187,174][i%4],angle=i/112*Math.PI*2;return `${i?'L':'M'}${200+Math.cos(angle)*radius} ${200+Math.sin(angle)*radius}`}).join(' ')+'Z M200 63a137 137 0 1 0 0 274a137 137 0 1 0 0-274Z'
let previousFocus,root,previousInert=false,previousOverflow=''
let stopArrival=()=>{}
watch(()=>state.phase,phase=>{
 stopArrival();stopArrival=()=>{}
 if(phase==='leave' && !state.reduced)stopArrival=playMenuArrival(state.kind)
},{flush:'post'})
function release(){
 if(root)root.inert=previousInert;root=null;document.body.style.overflow=previousOverflow
 const panel=[...document.querySelectorAll('.el-drawer,.el-dialog')].find(e=>e.getBoundingClientRect().height>0 && getComputedStyle(e).visibility!=='hidden')
 if(panel){panel.focus({preventScroll:true});return}
 if(previousFocus?.isConnected){previousFocus.focus({preventScroll:true});return}
 const target=document.querySelector('main h1,.creation-shell h2,.refraction-engine h2')
 if(target){target.setAttribute('tabindex','-1');target.focus({preventScroll:true})}
}
watch(()=>state.active,async active=>{
 if(active){previousFocus=document.activeElement;root=document.getElementById('app');previousInert=root?.inert || false;previousOverflow=document.body.style.overflow;if(root)root.inert=true;document.body.style.overflow='hidden';await nextTick();if(state.active)host.value?.focus({preventScroll:true})}
 else release()
})
function keydown(e){if(!state.active)return;if(e.key==='Escape'){e.preventDefault();menuTransition.skip()}else if(e.key==='Tab'){e.preventDefault();host.value?.querySelector('button')?.focus()}}
onMounted(()=>window.addEventListener('keydown',keydown))
onBeforeUnmount(()=>{stopArrival();window.removeEventListener('keydown',keydown);if(state.active){menuTransition.cancel();release()}})
</script>
