<template>
 <main class="folio-about" data-menu-destination="hand">
  <header class="about-masthead"><div class="brand-lockup"><CompassMark/><span>此生行记<small>创作札记 · 关于本作</small></span></div><button @click="$router.push('/home')">返回主菜单 ↗</button></header>
  <section class="about-opening"><div><p class="about-eyebrow">札记 / 04</p><h1 data-arrival="title">每一个选择，<br>都有回响。</h1><p>用自己的话，走进一个尚未写完的世界。</p></div><FlightMark data-arrival="ornament"/></section>
  <section class="about-principles" aria-label="这段旅程如何展开"><article data-arrival="item" v-for="(item,i) in principles" :key="item.title"><span>{{String(i+1).padStart(2,'0')}}</span><div><h2>{{item.title}}</h2><p>{{item.text}}</p></div></article></section>
  <footer class="about-footnote" data-arrival="body"><span>此生行记 · 众生有声</span><button @click="$router.push('/home')">回到启卷处 ←</button></footer>
 </main>
</template>
<script setup>
import {onMounted} from 'vue'
import CompassMark from '../components/CompassMark.vue'
import FlightMark from '../components/FlightMark.vue'
onMounted(()=>document.title='此生行记 · 创作札记')
const principles=[
 {title:'以行动续写',text:'用自己的话行动、交谈与同行。故事从你的选择开始，也会回应世界与人物各自的处境。'},
 {title:'众生各有来路',text:'每局先确立世界，人物根据各自的经历与意愿参与故事。一次相逢之后，还会有各自的后来。'},
 {title:'入梦与天眼',text:'入梦视角只呈现主角所知；天眼视角可以翻阅幕后人物资料。选择适合自己的观看方式。'},
 {title:'能力、时刻与回响',text:'知、慧、斗、闻、本、归体现不同能力。回溯已保存的时刻，也能从那里重新选择。'}
]
</script>
