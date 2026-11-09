<template><div class="perspective-switch" role="group" aria-label="游玩视角"><button v-for="mode in modes" :key="mode.id" :aria-pressed="modelValue===mode.id" :disabled="disabled || saving || modelValue===mode.id" :title="mode.note" @click="change(mode.id)">{{saving && target===mode.id?'切换中…':mode.label}}</button><span class="switch-error" v-if="error" role="alert">{{error}}</span></div></template>
<script setup>
import {ref,watch,onBeforeUnmount} from 'vue'
import http from '../api'
const props=defineProps({modelValue:String,sessionId:String,disabled:Boolean})
const emit=defineEmits(['change','pending'])
const saving=ref(false),target=ref(''),error=ref('');let sequence=0,alive=true
const modes=[{id:'DREAMER',label:'入梦 · 限知',note:'只查阅主角实际获知的内容'},{id:'OMNISCIENT',label:'天眼 · 全知',note:'额外查阅幕后资料，角色仍保有各自的限知'}]
async function change(mode){
 if(saving.value || props.disabled || !props.sessionId || props.modelValue===mode)return
 const request=++sequence,sid=props.sessionId,old=props.modelValue;saving.value=true;target.value=mode;error.value='';emit('pending',true)
 try{const result=await http.post('/game/view-mode',{sessionId:sid,viewMode:mode});if(!alive || request!==sequence || sid!==props.sessionId || old!==props.modelValue)return
  if(result.viewMode!==mode)throw new Error('view mismatch');emit('change',mode)
 }catch{if(alive && request===sequence && sid===props.sessionId)error.value='视角未保存，仍保留原模式。'}finally{if(alive && request===sequence){saving.value=false;emit('pending',false)}}
}
watch(()=>props.sessionId,()=>{sequence++;saving.value=false;error.value='';emit('pending',false)})
onBeforeUnmount(()=>{alive=false;sequence++})
</script>
<style scoped>.perspective-switch{display:flex;align-items:center;gap:4px;position:relative}.perspective-switch button{font-size:12px;padding:7px 9px;border:1px solid #a48b5144;color:#aab7c0;background:#14283a;border-radius:4px;cursor:pointer}.perspective-switch button[aria-pressed=true]{color:#e5cc93;border-color:#cdb074;background:#b99b4d15}.perspective-switch button:disabled:not([aria-pressed=true]){opacity:.5}.switch-error{position:absolute;top:100%;right:0;background:#172a3c;color:#efb2a0;white-space:nowrap;padding:6px;z-index:5}@media(max-width:600px){.perspective-switch button{font-size:11px;padding:5px}}</style>
