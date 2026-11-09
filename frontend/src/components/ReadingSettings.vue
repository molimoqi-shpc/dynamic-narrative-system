<template>
 <Teleport to="body"><Transition name="folio-float"><section v-if="open" id="reading-preferences" ref="panel" class="reading-float el-dialog engraved-reading" role="dialog" aria-modal="false" aria-labelledby="reading-preferences-title" tabindex="-1">
  <header class="el-dialog__header"><h2 id="reading-preferences-title" class="el-dialog__title">此间 · 阅读偏好</h2><button class="reading-close" aria-label="关闭阅读设置" @click="$emit('update:open',false)">×</button></header>
  <div class="el-dialog__body">
  <div class="reading-frontispiece"><EtchedOrnament kind="type"/><div><p class="utility-kicker">纸上万象，依你所好</p><small>字与行之间，留出自己的节奏。</small></div></div>
  <label v-for="(control,index) in controls" :key="control.key" class="reading-control"><span><i class="reading-control-index" aria-hidden="true">{{String(index+1).padStart(2,'0')}}</i>{{control.label}}<output>{{format(control)}}</output></span><input type="range" :aria-label="control.label" :min="control.min" :max="control.max" :step="control.step" :value="modelValue[control.key]" @input="change(control.key,Number($event.target.value))" /></label>
  <label class="reading-font"><span>正文字体</span><select aria-label="正文字体" :value="modelValue.font" @change="change('font',$event.target.value)"><option value="serif">宋体 · 书页</option><option value="sans">黑体 · 清晰</option><option value="kai">楷体 · 手记</option></select></label>
  <fieldset class="reading-narrative"><legend>正文篇幅 <small>下一次生成生效</small></legend><div role="group" aria-label="正文篇幅"><button v-for="option in narrativeOptions" :key="option.key" type="button" :aria-pressed="normalizeNarrativeMode(modelValue.narrativeMode)===option.key" @click="change('narrativeMode',option.key)"><strong>{{option.label}}</strong><span>{{option.description}}</span></button></div><p>已有正文不重写；进行中的故事沿用提交时的篇幅模式。</p></fieldset>
  <fieldset class="reading-motion"><legend>动效强度 <small>全站生效</small></legend><div role="group" aria-label="动效强度"><button v-for="option in motionOptions" :key="option.key" type="button" :aria-pressed="motionPreferences.mode===option.key" @click="setMotionMode(option.key)">{{option.label}}</button></div><p>{{motionPreferences.systemReduced?'系统已开启减少动态效果，当前以静态呈现。':motionDescription}}</p></fieldset>
  <div class="reading-proof-label"><span>试读纸页</span><i aria-hidden="true">Aa / 字</i></div><p class="reading-sample" :style="readingVariables(modelValue)">风过长廊，旧信未凉。<br>此后万里，皆有回响。</p>
  <footer><small>偏好仅保存在此设备<br>字体使用设备已有字体，缺失时自动回退。</small><button @click="resetPreferences">恢复默认</button></footer>
  </div>
 </section></Transition></Teleport>
</template>
<script setup>
import {ref,watch,nextTick,onMounted,onBeforeUnmount,computed} from 'vue'
import {motionPreferences,setMotionMode} from '../utils/motionPreferences'
import EtchedOrnament from './EtchedOrnament.vue'
import {readingDefaults,readingVariables,normalizeNarrativeMode} from '../utils/readingPreferences'
const props=defineProps({open:Boolean,modelValue:{type:Object,required:true}})
const emit=defineEmits(['update:open','update:modelValue'])
defineOptions({inheritAttrs:false})
const panel=ref(null);let previousFocus=null
watch(()=>props.open,async open=>{if(open){previousFocus=document.activeElement;await nextTick();panel.value?.focus()}else if(panel.value?.contains(document.activeElement)){previousFocus?.focus()}},{immediate:true})
function escape(event){if(props.open && event.key==='Escape')emit('update:open',false)}
onMounted(()=>window.addEventListener('keydown',escape))
onBeforeUnmount(()=>window.removeEventListener('keydown',escape))
const controls=[{key:'size',label:'字号',min:13,max:24,step:1,unit:'px'},{key:'spacing',label:'字距',min:0,max:3,step:.05,unit:'px'},{key:'leading',label:'行距',min:1.5,max:2.8,step:.05,unit:'倍'},{key:'density',label:'段落留白',min:.4,max:1.6,step:.1,unit:'倍'}]
const narrativeOptions=[{key:'NORMAL',label:'正常篇幅',description:'按情节轻重，至少500字；丰富时可数千字。'},{key:'LONG',label:'长篇沉浸',description:'更充分展开关键场景与人物，减少概述。'}]
const motionOptions=[{key:'FULL',label:'完整'},{key:'LIGHT',label:'轻量'},{key:'STATIC',label:'静态'}]
const motionDescription=computed(()=>({FULL:'保留完整入场、转场与光影；再次打开存档也完整播放。',LIGHT:'保留简短淡入，收起持续摆动与鼠标光影。',STATIC:'直接呈现内容，保留选中标记与操作反馈。'}[motionPreferences.mode]))
function resetPreferences(){emit('update:modelValue',{...readingDefaults});setMotionMode('FULL')}
function change(key,value){emit('update:modelValue',{...props.modelValue,[key]:value})}
function format(c){return Number(props.modelValue[c.key].toFixed(2))+' '+c.unit}
</script>
<style scoped>
.reading-narrative{border:0;border-block:1px solid #becbd550;padding:15px 0 13px;margin:16px 0;min-width:0}.reading-narrative legend{float:left;width:100%;box-sizing:border-box;font:11px/1.8 SimSun,serif;color:#4d6b86;margin-bottom:10px}.reading-narrative legend small{float:right;font:9px/1.8 system-ui;color:#8398aa;letter-spacing:1px}.reading-narrative>div{clear:both;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.reading-narrative button{font:11px SimSun,serif;text-align:left;background:#f8f8f1;border:1px solid #b6c6d3;padding:10px;color:#688198;cursor:pointer}.reading-narrative button[aria-pressed=true]{border-color:#5f81a1;color:#2a5175;background:linear-gradient(110deg,#dfe9f1,#edf1ed);box-shadow:inset 0 -2px #507597}.reading-narrative button:focus-visible{outline:2px solid #5f81a1;outline-offset:2px}.reading-narrative button strong{display:block;font-weight:500;font-size:12px;letter-spacing:1px;margin-bottom:7px}.reading-narrative button span,.reading-narrative>p{display:block;font:10px/1.85 system-ui;color:#70879b}.reading-narrative>p{margin:10px 0 0}
</style>
