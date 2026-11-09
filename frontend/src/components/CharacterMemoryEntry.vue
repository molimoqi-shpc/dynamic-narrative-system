<template>
 <article class="observer-memory memory-reading" :class="{'memory-subjective':record.subjective}">
  <header class="memory-heading"><div><span class="memory-kind">{{record.type}}</span><h4>{{record.title}}</h4></div><span v-if="record.start!=null || record.end!=null" class="memory-time">{{timeLabel}}</span><span v-else class="memory-time">过往留存</span></header>
  <p v-if="record.subjective" class="memory-intention-note">当时的想法或意愿，不等于已经完成的行动。</p>
  <div class="memory-passage"><section v-for="(block,index) in record.blocks" :key="`${block.key}:${index}`" class="memory-prose-block"><h5 v-if="block.label">{{block.label}}</h5><p v-for="(paragraph,i) in block.paragraphs" :key="i">{{paragraph}}</p></section></div>
  <p v-if="!record.blocks.length && !record.details" class="observer-note">这条记录尚无可读正文。</p>
  <details v-if="record.details" class="memory-record-details" :open="!record.blocks.length"><summary>完整记录与相关状态</summary><DossierFields reading :names="names" :value="record.details"/></details>
 </article>
</template>
<script setup>
import {computed} from 'vue'
import {characterMemory} from '../utils/characterMemory'
import {storyMoment} from '../utils/storyTime'
import DossierFields from './DossierFields.vue'
const props=defineProps({entry:{type:Object,required:true},names:{type:Object,default:()=>({})},currentSeconds:Number})
const record=computed(()=>characterMemory(props.entry,props.names))
const timeLabel=computed(()=>[record.value.start,record.value.end].filter((value,index,array)=>value!=null && array.indexOf(value)===index).map(value=>storyMoment(value,{currentSeconds:props.currentSeconds})).join(' → '))
</script>
<style scoped>
.memory-reading{min-width:0;position:relative}.memory-reading .memory-heading{display:flex;align-items:flex-start;justify-content:space-between;flex-wrap:wrap;gap:10px 18px;margin:0 0 15px;padding-bottom:12px;border-bottom:1px solid #a4b7c84d}.memory-heading>div{min-width:0}.memory-heading h4{margin:5px 0 0;font:17px/1.6 SimSun,serif;letter-spacing:.5px;color:inherit}.memory-kind{font:10px/1.7 system-ui;letter-spacing:1px;color:#6a839b}.memory-time{font:10px/1.8 system-ui;color:#788ea3;max-width:100%;overflow-wrap:break-word}.memory-passage{min-width:0}.memory-prose-block+section{margin-top:18px}.memory-prose-block h5{font:11px/1.8 system-ui;color:#6d859a;margin:0 0 6px}.memory-prose-block p{font:14px/2.05 "Noto Serif SC",SimSun,serif;margin:0 0 13px;white-space:pre-wrap;word-break:normal;overflow-wrap:break-word}.memory-prose-block p:last-child{margin-bottom:0}.memory-intention-note{margin:0 0 14px;padding:7px 11px;border-left:2px solid #b39971;background:#b399710b;font:11px/1.9 system-ui;color:#8b795e}.memory-reading>.memory-record-details{margin:18px 0 0;padding:12px 0 0;border:0;border-top:1px solid #adbed247;border-radius:0;background:transparent}.memory-record-details summary{font:11px/1.9 system-ui;color:#617c96;cursor:pointer;margin-bottom:8px}@media(max-width:520px){.memory-heading h4{font-size:16px}.memory-prose-block p{font-size:13px;line-height:2}.memory-time{flex-basis:100%}}
</style>
