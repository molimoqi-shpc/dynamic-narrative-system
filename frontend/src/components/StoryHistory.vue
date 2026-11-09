<template>
 <section class="story-archive" :style="readingStyle">
  <header class="archive-intro"><span>往事 / 留存的篇章</span><p>从来路，到此刻。每一步都在这里。</p><slot name="actions" /></header>
  <p v-if="error" role="alert">旧卷暂未读到。<button @click="$emit('retry')">重新读取</button></p>
  <p v-if="loading" role="status">正在翻阅旧卷…</p>
  <div v-if="entries.length" class="archive-layout">
   <nav aria-label="往事篇目"><button v-if="hasMore" :disabled="loading" @click="$emit('older')">更早篇章 ↑</button><button v-for="entry in entries" :key="entry.key" :aria-pressed="selected===entry.key" @click="selected=entry.key"><small>{{entry.key==='opening'?'序 / 00':'卷 / '+entry.round}}</small><span>{{entry.title}}</span></button></nav>
   <article v-if="current" class="archive-passage"><header><small>{{current.key==='opening'?'初始故事':'已发生的故事'}}</small><h2>{{current.title}}</h2><p v-if="current.input" class="archive-input">当时的行动 · {{current.input}}</p></header><div class="story-prose"><p v-for="(p,i) in current.text.split(/\n\s*\n+/).filter(Boolean)" :key="i">{{p}}</p></div></article>
  </div>
  <p v-else-if="!loading && !error" class="archive-empty">此存档暂未留存故事。开局故事完成后会在此收录。</p>
 </section>
</template>
<script setup>
import {computed,ref,watch} from 'vue'
const props=defineProps({opening:Object,turns:{type:Array,default:()=>[]},loading:Boolean,error:Boolean,hasMore:Boolean,readingStyle:Object,storyText:{type:Function,required:true},clean:{type:Function,default:t=>t}})
defineEmits(['older','retry'])
const entries=computed(()=>[...(props.opening?.text?[{key:'opening',title:props.opening.title || '序章 · 来路',text:props.clean(props.opening.text)}]:[]),...props.turns.map(t=>({key:String(t.id),round:t.round || '—',title:t.chapterTitle || `第 ${t.round || '—'} 段故事`,input:props.clean(t.playerInput),text:props.storyText(t)}))])
const selected=ref('opening')
watch(entries,value=>{if(!value.some(e=>e.key===selected.value))selected.value=value[0]?.key || ''},{immediate:true})
const current=computed(()=>entries.value.find(e=>e.key===selected.value))
</script>
