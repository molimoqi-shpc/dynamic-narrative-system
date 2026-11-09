<template>
 <figure class="sixfold-chart"><svg viewBox="0 0 280 270" aria-hidden="true"><g fill="none" stroke="currentColor"><polygon v-for="scale in [.25,.5,.75,1]" :key="scale" :points="outline(scale)"/><path v-for="(_,i) in statDefinitions" :key="i" :d="`M140 132 ${point(i,92).join(' ')}`"/></g><polygon class="sixfold-value" :points="shape"/><circle v-for="(stat,i) in statDefinitions" :key="stat.key" :cx="point(i,92*ratio(stat.key))[0]" :cy="point(i,92*ratio(stat.key))[1]" r="3"/><text v-for="(stat,i) in statDefinitions" :key="`label-${stat.key}`" :x="point(i,118)[0]" :y="point(i,118)[1]+5" text-anchor="middle">{{stat.label}}</text></svg><figcaption>六维轮廓 · 按当前数值相对绘制</figcaption></figure>
</template>
<script setup>
import {computed} from 'vue'
import {statDefinitions} from '../utils/sceneGuidance'
const props=defineProps({stats:Object})
const value=key=>Math.max(0,Number(props.stats?.[key]) || 0)
const maximum=computed(()=>Math.max(1,...statDefinitions.map(stat=>value(stat.key))))
const ratio=key=>value(key)/maximum.value
const point=(i,r)=>[140+Math.cos(i*Math.PI/3-Math.PI/2)*r,132+Math.sin(i*Math.PI/3-Math.PI/2)*r]
const outline=scale=>statDefinitions.map((_,i)=>point(i,92*scale).join(',')).join(' ')
const shape=computed(()=>statDefinitions.map((stat,i)=>point(i,92*ratio(stat.key)).join(',')).join(' '))
</script>
<style scoped>.sixfold-chart{margin:0;color:#8598b644}.sixfold-chart svg{width:100%;display:block;overflow:visible;stroke-width:.8}.sixfold-value{fill:#315bd827;stroke:#315bd8;stroke-width:1.5}.sixfold-chart circle{fill:#315bd8}.sixfold-chart text{fill:#405773;font:15px SimSun,serif}.sixfold-chart figcaption{text-align:center;color:#7b8ba0;font:9px/1.8 system-ui;letter-spacing:1px}</style>
