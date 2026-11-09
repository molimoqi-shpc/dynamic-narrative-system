<template>
 <div class="main-model-control"><button ref="trigger" class="main-model-trigger" type="button" aria-controls="main-model-panel" :aria-expanded="open" aria-label="切换主 API" @click="toggle"><span>主创</span><small>{{model==='deepseek-v4-pro'?'Pro':model==='deepseek-v4-flash'?'Flash':'模型'}}</small></button>
  <Teleport to="body"><Transition name="folio-float"><section v-if="open" id="main-model-panel" ref="panel" class="main-model-panel" role="dialog" aria-modal="false" aria-labelledby="main-model-title" tabindex="-1">
   <header><div><p>STORYTELLER / 主创通道</p><h2 id="main-model-title">主 API · 模型选择</h2></div><button type="button" aria-label="关闭主 API 选择" @click="close">×</button></header>
   <p class="main-model-loading" v-if="loading" role="status">正在读取当前模型…</p>
   <div class="main-model-options" role="radiogroup" aria-label="主 API 模型"><button v-for="option in options" :key="option.id" type="button" role="radio" :aria-checked="model===option.id" :disabled="loading || saving || !model || model===option.id" @click="change(option.id)"><span class="main-model-radio" aria-hidden="true"/><span><strong>{{option.label}}</strong><small>{{option.note}}</small></span><i v-if="model===option.id">当前</i><i v-else-if="saving && target===option.id">保存中</i></button></div>
   <p v-if="error" class="main-model-error" role="alert">{{error}} <button v-if="!model" type="button" @click="load">重新读取</button></p>
   <p v-else-if="notice" class="main-model-notice" role="status">{{notice}}</p>
   <footer><span>{{busy?'正在生成的这一段保持原模型，新选择从下一次行动生效。':'选择保存在当前存档，从下一次行动生效。'}}</span><small>角色 API 保持原通道。切换本身不调用模型；Pro 的生成费用高于 Flash。</small></footer>
  </section></Transition></Teleport>
 </div>
</template>
<script setup>
import {ref,watch,nextTick,onMounted,onBeforeUnmount} from 'vue'
import http from '../api'
import {useSessionStore} from '../store/session'
import {requestIdentity} from '../utils/requestIdentity'
const props=defineProps({sessionId:String,busy:Boolean})
const emit=defineEmits(['opened'])
const session=useSessionStore(),open=ref(false),model=ref(''),loading=ref(false),saving=ref(false),target=ref(''),error=ref(''),notice=ref(''),panel=ref(null),trigger=ref(null)
const options=[{id:'deepseek-v4-flash',label:'DeepSeek Flash',note:'响应更快，费用较低'},{id:'deepseek-v4-pro',label:'DeepSeek Pro',note:'更强的创作与推理，费用更高'}]
let alive=true,sequence=0
const settingTimeout=10000
async function readModel(sid){const result=await http.get('/game/main-model',{params:{sessionId:sid},timeout:settingTimeout});if(!options.some(option=>option.id===result.model))throw new Error('未知模型');return result.model}
async function toggle(){if(open.value){close();return}open.value=true;emit('opened');await nextTick();panel.value?.focus()}
function close(restore=true){open.value=false;if(restore)trigger.value?.focus()}
function validRequest(number,sid){const identity=requestIdentity(session,()=>alive && sequence===number,{allowBranchChange:true});return ()=>identity() && sid===props.sessionId}
async function load(){
 const sid=props.sessionId,number=++sequence;model.value='';error.value='';notice.value='';saving.value=false
 if(!sid){loading.value=false;return}const valid=validRequest(number,sid);loading.value=true
 try{const selected=await readModel(sid);if(valid())model.value=selected}
 catch{if(valid())error.value='暂未读取到主 API 设置，请重试。'}finally{if(valid())loading.value=false}
}
async function change(value){
 if(loading.value || saving.value || !model.value || !props.sessionId || model.value===value)return
 const sid=props.sessionId,number=++sequence,valid=validRequest(number,sid);saving.value=true;target.value=value;error.value='';notice.value=''
 try{const result=await http.post('/game/main-model',{sessionId:sid,model:value},{timeout:settingTimeout});if(!valid())return;if(result.model!==value)throw new Error('保存结果不一致');model.value=value;notice.value='已保存，下次行动使用 '+options.find(o=>o.id===value).label+'。'}
 catch{
  if(!valid())return
  // A timeout cannot tell us whether the setting committed. Read once without
  // repeating the write or calling a story model.
  model.value=''
  try{const selected=await readModel(sid);if(!valid())return;model.value=selected
   if(selected===value)notice.value='已核对保存结果，下次行动使用 '+options.find(o=>o.id===selected).label+'。'
   else error.value='已核对，仍使用原模型 '+options.find(o=>o.id===selected).label+'。'
  }catch{if(valid())error.value='保存结果尚未确认，请重新读取当前模型。'}
 }finally{if(valid()){saving.value=false;target.value=''}}
}
function keydown(event){if(open.value && event.key==='Escape'){event.preventDefault();close()}}
function outside(event){if(open.value && !panel.value?.contains(event.target) && !trigger.value?.contains(event.target))close(false)}
function dismiss(){close(false)}
watch(()=>[props.sessionId,session.playerId,session.sessionGeneration],()=>{open.value=false;void load()},{immediate:true})
onMounted(()=>{window.addEventListener('keydown',keydown);window.addEventListener('pointerdown',outside);window.addEventListener('close-main-model-panel',dismiss)})
onBeforeUnmount(()=>{alive=false;sequence++;window.removeEventListener('keydown',keydown);window.removeEventListener('pointerdown',outside);window.removeEventListener('close-main-model-panel',dismiss)})
</script>
<style scoped>
.main-model-trigger small{display:inline;font:9px sans-serif;letter-spacing:0;color:#7b91a2}
.main-model-panel{position:fixed;left:165px;bottom:35px;z-index:2200;width:min(345px,calc(100vw - 32px));padding:23px;background:#f0f2ed;color:#2d485f;border:1px solid #a0b4c4;box-shadow:7px 12px 35px #193a5030;outline:none;box-sizing:border-box}.main-model-panel::before{content:'';position:absolute;inset:7px;border:1px solid #c6d2d8;pointer-events:none}.main-model-panel header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;border-bottom:1px solid #becbd3;padding-bottom:18px}.main-model-panel header p{margin:0 0 8px;font:9px sans-serif;letter-spacing:1.5px;color:#8295a3}.main-model-panel h2{font:19px/1.5 serif;letter-spacing:1px;margin:0}.main-model-panel header>button{border:0;background:none;color:#617e94;font:23px sans-serif;cursor:pointer}.main-model-options{display:grid;gap:10px;margin:18px 0}.main-model-options>button{display:flex;align-items:center;gap:12px;padding:15px 10px;background:transparent;border:1px solid #bdcbd4;text-align:left;color:inherit;cursor:pointer}.main-model-options>button[aria-checked=true]{border-color:#537b9d;background:#e3eaf0}.main-model-options>button:disabled:not([aria-checked=true]){opacity:.5;cursor:wait}.main-model-radio{width:12px;height:12px;border:1px solid #718da3;transform:rotate(45deg);flex:none;box-sizing:border-box}.main-model-options>button[aria-checked=true] .main-model-radio{background:#527a9b;box-shadow:inset 0 0 0 2px #e3eaf0}.main-model-options strong{font:14px serif}.main-model-options small{display:block;font:10px/1.6 sans-serif;margin-top:5px;color:#7b8f9e}.main-model-options i{margin-left:auto;white-space:nowrap;font:9px sans-serif;color:#5d7f9a}.main-model-loading,.main-model-error,.main-model-notice{font:11px/1.8 sans-serif}.main-model-error{color:#9a514c}.main-model-error button{color:inherit;border:0;background:transparent;text-decoration:underline;cursor:pointer}.main-model-notice{color:#426c84}.main-model-panel footer{border-top:1px solid #bdcbd4;padding-top:14px;display:grid;gap:8px;font:11px/1.8 sans-serif;color:#6a8193}.main-model-panel footer small{font-size:10px;color:#8a9ca8}.main-model-panel button:focus-visible{outline:2px solid #5b82a0;outline-offset:3px}@media(max-width:700px){.main-model-panel{left:16px;bottom:85px}}
</style>
