<template>
 <section ref="stage" class="paper-stage refraction-stage" :class="['paper-stage-'+atmosphere.key,'portrait-'+(portrait.key || 'unknown'),{'portrait-cinematic':portrait.cinematic,'stage-night':artDirection.night,'stage-railway':backdrop?.includes('stage-clock-station'),'motion-paused':!pageVisible || !inView}]" :style="{'--stage-accent':artDirection.accent,'--silhouette-cutout':`url(#${silhouetteId})`,'--symbolic-cutout':`url(#${symbolicId})`}" aria-label="此间人物" @pointermove="parallax" @pointerleave="resetParallax">
  <svg class="stage-filter-defs" width="0" height="0" aria-hidden="true" focusable="false"><defs><filter :id="silhouetteId" color-interpolation-filters="sRGB"><feComponentTransfer><feFuncA type="discrete" tableValues="0 0 0 0 0 0 1 1 1 1" /></feComponentTransfer><feColorMatrix type="matrix" :values="artDirection.night?'0.053 0.179 0.018 0 0.08 0.074 0.250 0.026 0 0.10 0.106 0.358 0.036 0 0.20 0 0 0 1 0':'0.018 0.060 0.006 0 0.008 0.034 0.114 0.012 0 0.035 0.060 0.200 0.020 0 0.090 0 0 0 1 0'" /></filter><filter :id="symbolicId" color-interpolation-filters="sRGB"><feComponentTransfer><feFuncA type="discrete" tableValues="0 0 0 0 0 1 1 1 1 1" /></feComponentTransfer><feColorMatrix type="matrix" values="0.170 0.570 0.060 0 0.025 0.190 0.640 0.065 0 0.050 0.210 0.710 0.072 0 0.090 0 0 0 1 0" /></filter></defs></svg>
  <Transition name="stage-backdrop"><img v-if="backdrop" :key="backdrop" class="paper-landscape" :src="backdrop" alt="" /></Transition>
  <div class="refraction-plane" aria-hidden="true" /><div class="paper-distance" aria-hidden="true" /><div class="hero-seams" aria-hidden="true" />
  <svg class="stage-orbit" viewBox="0 0 720 720" preserveAspectRatio="xMaxYMid slice" aria-hidden="true" focusable="false"><path d="M720 65C600 75 570 214 608 412M720 224C653 198 615 220 599 266" /><path class="orbit-tick" d="m603 382 10 0m-5-5 0 10" /></svg>
  <div class="stage-light" aria-hidden="true"/><p class="stage-place">{{scene.locationName || '旅途将启'}}<span>{{scene.worldSetting?.title || atmosphere.label}}</span></p>
  <Transition name="cast-dissolve" appear>
   <div :key="portrait.src || 'unknown'" class="refraction-actor" :class="['portrait-'+(portrait.key || 'unknown'),{'portrait-cinematic':portrait.cinematic,'portrait-framed':!!portrait.framing}]" :style="portrait.framing?.stage">
    <div class="paper-person-wrap" :class="{'paper-person-child':portrait.key==='child-girl'}">
     <div v-if="inlineFallback" class="portrait-silhouette symbolic-figure" v-html="portrait.markup" aria-hidden="true" />
     <img v-else class="portrait-silhouette" :src="portrait.src" alt="" aria-hidden="true" />
     <div class="portrait-float"><div v-if="inlineFallback" class="paper-person symbolic-figure" v-html="portrait.markup" aria-hidden="true" /><img v-else class="paper-person" :class="{'symbolic-portrait':portrait.symbolic}" :src="portrait.src" alt="" decoding="async" @error="onPortraitError" />
     </div>
    </div>
    <div class="paper-foreground" aria-hidden="true" />
    <div v-folio-motion.update="person?.id" class="stage-caption"><p class="stage-caption-kicker">{{!person?'行旅留影':portrait.symbolic?'剪影 · 此间人物':scene.presenceSource==='OBSERVED'?'最近所见':'初至所见'}}</p><h2>{{person?.name || '此刻独行'}}</h2><p class="stage-description">{{person?.publicAppearance?.basis || person?.introduction || (person?'此间相逢，故事尚在展开。':'前路仍在延展。')}}</p><div class="stage-caption-actions"><button @click="$emit('book')">人物册 ↗</button><button v-if="person" :disabled="busy" @click="$emit('talk',person)">亲自交谈</button></div></div>
   </div>
  </Transition>
  <details v-if="people.length" ref="castMenu" class="stage-cast-menu"><summary><TheatreGlyph name="person"/><span>此间人物 <b>{{people.length}}</b></span><i>＋</i></summary><div class="stage-cast-list"><header><span>相逢录</span><small>依公开外貌呈现</small></header><button v-for="(p,index) in people" :key="p.id" :aria-pressed="p.id===person?.id" @click="select(p.id)"><span class="cast-portrait-crop"><img v-if="characterPortrait(p,viewMode,scene.worldSetting).src" :src="characterPortrait(p,viewMode,scene.worldSetting).src" :style="characterPortrait(p,viewMode,scene.worldSetting).framing?.thumbnail" alt="" loading="lazy" decoding="async" /><span v-else class="cast-letter">{{p.name.slice(0,1)}}</span></span><span class="cast-person-name">{{p.name}}<small>{{p.id===person?.id?'此刻注目':'切换人物形象'}}</small></span><span class="cast-number">{{String(index+1).padStart(2,'0')}}</span></button></div></details>
  <nav v-if="people.length>1" class="cast-switch" aria-label="切换此间人物"><button aria-label="上一位人物" @click="stepCast(-1)">‹</button><span>{{String(castIndex+1).padStart(2,'0')}} / {{String(people.length).padStart(2,'0')}}</span><button aria-label="下一位人物" @click="stepCast(1)">›</button></nav>
 </section>
</template>
<script setup>
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue'
import {characterPortrait,sceneCast,portraitStage,sceneBackdrop} from '../utils/characterPortrait'
import {symbolicPortrait} from '../utils/portraitFallback'
import TheatreGlyph from './TheatreGlyph.vue'
import {folioMotion as vFolioMotion} from '../utils/folioMotion'
import {currentMotion,MOTION_EVENT} from '../utils/motionPreferences'
const props=defineProps({scene:{type:Object,default:()=>({})},atmosphere:{type:Object,required:true},viewMode:String,busy:Boolean,identity:String})
defineEmits(['talk','book','scene'])
const stage=ref(null),castMenu=ref(null),selected=ref(''),imageFailed=ref(false),fallbackFailed=ref(false),pageVisible=ref(true),inView=ref(true)
// Rendering-only ink mask; discard the source's soft ambient alpha in the shadow,
// while preserving the approved full-color illustration untouched.
const silhouetteId='cast-ink-'+Math.random().toString(36).slice(2,10)
const symbolicId=silhouetteId+'-anonymous'
const people=computed(()=>sceneCast(props.scene))
const person=computed(()=>people.value.find(p=>p.id===selected.value) || people.value.find(p=>characterPortrait(p,props.viewMode,props.scene.worldSetting).src) || people.value[0])
const castIndex=computed(()=>Math.max(0,people.value.findIndex(p=>p.id===person.value?.id)))
const sourcePortrait=computed(()=>characterPortrait(person.value,props.viewMode,props.scene.worldSetting))
const portrait=computed(()=>imageFailed.value || !sourcePortrait.value.src?symbolicPortrait(person.value || {id:'journey-alone'},props.viewMode,props.scene.worldSetting,sourcePortrait.value):sourcePortrait.value)
const inlineFallback=computed(()=>portrait.value.symbolic && fallbackFailed.value)
const artDirection=computed(()=>portraitStage(portrait.value))
const backdrop=computed(()=>artDirection.value.backdrop || sceneBackdrop(props.scene,props.atmosphere))
watch(()=>[props.identity,props.scene.sceneId],()=>{switchSequence++;requested='';selected.value='';resetParallax();if(castMenu.value)castMenu.value.open=false},{flush:'sync'})
watch(()=>[sourcePortrait.value.src,person.value?.id],()=>{imageFailed.value=false;fallbackFailed.value=false})
function onPortraitError(){if(portrait.value.symbolic)fallbackFailed.value=true;else imageFailed.value=true}
let switchSequence=0,requested=''
const decoded=new Map()
function prepare(src){if(!src)return Promise.resolve();if(decoded.has(src))return decoded.get(src);const img=new Image();img.src=src;const ready=typeof img.decode==='function'?img.decode().catch(()=>{}):Promise.resolve();decoded.set(src,ready);return ready}
async function select(id,fromMenu=true){
 requested=id;const seq=++switchSequence,p=people.value.find(p=>p.id===id);if(!p)return
 const art=characterPortrait(p,props.viewMode,props.scene.worldSetting)
 if(castMenu.value?.open){castMenu.value.open=false;if(fromMenu)castMenu.value.querySelector('summary')?.focus()}
 await Promise.all([prepare(art.src),prepare(portraitStage(art).backdrop)])
 if(seq!==switchSequence || !people.value.some(p=>p.id===id))return
 selected.value=id
}
function stepCast(offset){if(!people.value.length)return;const index=people.value.findIndex(p=>p.id===(requested || person.value?.id));select(people.value[(Math.max(0,index)+offset+people.value.length)%people.value.length].id,false)}
watch(()=>portrait.value.src,src=>{prepare(src);const next=people.value[(castIndex.value+1)%people.value.length];if(next)prepare(characterPortrait(next,props.viewMode,props.scene.worldSetting).src)},{immediate:true})
let frame=0,observer
function parallax(e){
 if(e.pointerType!=='mouse' || currentMotion()!=='FULL')return
 const r=stage.value?.getBoundingClientRect();if(!r)return
 cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{if(!stage.value)return;stage.value.style.setProperty('--stage-x',((e.clientX-r.left)/r.width-.5)*12+'px');stage.value.style.setProperty('--stage-y',((e.clientY-r.top)/r.height-.5)*7+'px');stage.value.style.setProperty('--light-x',((e.clientX-r.left)/r.width)*100+'%')})
}
function resetParallax(){cancelAnimationFrame(frame);stage.value?.style.setProperty('--stage-x','0px');stage.value?.style.setProperty('--stage-y','0px')}
function visibility(){pageVisible.value=!document.hidden}
onMounted(()=>{visibility();window.addEventListener(MOTION_EVENT,resetParallax);document.addEventListener('visibilitychange',visibility);if(typeof IntersectionObserver!=='undefined'){observer=new IntersectionObserver(entries=>{inView.value=entries[0]?.isIntersecting!==false});observer.observe(stage.value)}})
onBeforeUnmount(()=>{window.removeEventListener(MOTION_EVENT,resetParallax);switchSequence++;cancelAnimationFrame(frame);observer?.disconnect();document.removeEventListener('visibilitychange',visibility)})
</script>
