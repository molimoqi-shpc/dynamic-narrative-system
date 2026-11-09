<template>
 <div v-if="depth>=maxDossierDepth" class="dossier-empty">更多细节暂不展开。</div>
 <div v-else-if="empty" class="dossier-empty">尚未记录</div>
 <dl v-else-if="typeof display==='object' && !Array.isArray(display)" class="dossier-fields" :class="{'dossier-reading':reading}">
  <div v-for="[key,child] in entries" :key="key" :class="{'dossier-field-group':grouped(child)}"><dt>{{dossierLabel(key)}}</dt><dd><DossierFields :value="child" :field-name="String(key)" :names="names" :depth="depth+1" :reading="reading" /></dd></div>
 </dl>
 <ul v-else-if="Array.isArray(display)" class="dossier-list" :class="{'dossier-tags':reading && shortList}"><li v-for="(child,index) in display" :key="index"><DossierFields :value="child" :field-name="fieldName" :names="names" :depth="depth+1" :reading="reading" /></li></ul>
 <span v-else class="dossier-text">{{timeFields.has(fieldName)?storyMoment(display):dossierText(display,fieldName,names)}}</span>
</template>
<script setup>
import {storyMoment} from '../utils/storyTime'
import {computed} from 'vue'
import {dossierLabel} from '../utils/dossierLabels.js'
import {readDossierValue,dossierEntries,dossierText,maxDossierDepth} from '../utils/dossierPresentation'
const props=defineProps({value:{default:null},fieldName:String,names:{type:Object,default:()=>({})},depth:{type:Number,default:0},reading:Boolean})
const display=computed(()=>readDossierValue(props.value))
const entries=computed(()=>display.value && typeof display.value==='object' && !Array.isArray(display.value)?dossierEntries(display.value):[])
const shortList=computed(()=>Array.isArray(display.value) && display.value.every(child=>typeof child==='string' && child.length<=24 && !child.includes('\n') && typeof readDossierValue(child)==='string'))
function grouped(value){const decoded=readDossierValue(value);return decoded!==null && typeof decoded==='object'}
const empty=computed(()=>display.value==null || typeof display.value==='string' && !display.value.trim() || typeof display.value==='object' && (Array.isArray(display.value)?!display.value.length:!entries.value.length))
const timeFields=new Set(['observedAtSeconds','snapshotAtSeconds','recordedAtSeconds','dossierSnapshotSeconds','storySeconds','occurredAtSeconds','atSeconds'])
</script>
<style scoped>
.dossier-fields{margin:0}.dossier-fields>div{margin:.45rem 0;padding-left:.7rem;border-left:1px solid #c4ab6838}.dossier-fields dt{font-size:.8rem;color:#bdae83}.dossier-fields dd{margin:.2rem 0 0}.dossier-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.75}.dossier-empty{color:#a5a194;font-size:.85rem}.dossier-list{padding-left:1.2rem;margin:.3rem 0}.dossier-list li{margin:.3rem 0}
.dossier-fields.dossier-reading{min-width:0}.dossier-fields.dossier-reading>div{display:grid;grid-template-columns:minmax(80px,112px) minmax(0,1fr);gap:14px;margin:0;padding:10px 0;border-left:0;border-bottom:1px solid #9caec329;min-width:0}.dossier-fields.dossier-reading>div:last-child{border-bottom:0}.dossier-fields.dossier-reading dt{line-height:1.85;overflow-wrap:break-word}.dossier-fields.dossier-reading dd{min-width:0;margin:0}.dossier-fields.dossier-reading>div.dossier-field-group{display:block}.dossier-fields.dossier-reading>.dossier-field-group>dt{margin-bottom:7px;font-weight:600}.dossier-tags{display:flex;flex-wrap:wrap;gap:6px 9px;padding:0;list-style:none}.dossier-tags>li{margin:0;padding:1px 7px;background:#9eafc414;border:1px solid #9eafc429}.dossier-reading .dossier-text{word-break:normal;overflow-wrap:break-word}
@media(max-width:520px){.dossier-fields.dossier-reading>div{grid-template-columns:minmax(64px,84px) minmax(0,1fr);gap:9px}}
</style>
