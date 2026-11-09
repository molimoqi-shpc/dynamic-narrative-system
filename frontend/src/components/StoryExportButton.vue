<template>
 <button v-if="rail" class="story-export-rail" type="button" :disabled="disabled || loading || !sessionId" :aria-busy="loading" aria-label="导出当前存档故事文本" title="导出当前存档的全部已发表故事（TXT）" @click.stop="exportStory"><span>{{loading?'导出中':'导出'}}</span><small>TXT</small></button>
 <el-button v-else class="story-export-button" size="small" text :loading="loading" :disabled="disabled || !sessionId" :aria-busy="loading" title="导出此存档当前分支的全部已发表故事（TXT）" @click.stop="exportStory">{{loading?'正在导出':'导出故事'}}</el-button>
</template>
<script setup>
import {ref,onBeforeUnmount,watch} from 'vue'
import {ElButton,ElMessage} from 'element-plus'
import {useSessionStore} from '../store/session'
import {requestIdentity} from '../utils/requestIdentity'
import {downloadStory} from '../utils/storyExport'

const props=defineProps({sessionId:String,disabled:Boolean,rail:Boolean})
const session=useSessionStore(),loading=ref(false)
let alive=true,sequence=0,controller=null
function invalidate(){sequence++;controller?.abort();controller=null;loading.value=false}
watch(()=>props.sessionId,invalidate)
onBeforeUnmount(()=>{alive=false;invalidate()})
async function exportStory(){
 if(loading.value || props.disabled || !props.sessionId || !session.playerId)return
 const ticket=++sequence,sid=props.sessionId,token=localStorage.getItem('bs_token')
 const owner=requestIdentity(session,()=>alive && sequence===ticket,{allowBranchChange:true})
 const valid=()=>owner() && props.sessionId===sid && token===localStorage.getItem('bs_token')
 controller=new AbortController();loading.value=true
 try{await downloadStory(sid,{valid,signal:controller.signal})}
 catch(error){if(valid() && error?.code!=='ERR_CANCELED' && error?.name!=='AbortError')ElMessage.error(error.message || '故事导出失败，请重试。')}
 finally{if(alive && ticket===sequence){loading.value=false;controller=null}}
}
</script>
