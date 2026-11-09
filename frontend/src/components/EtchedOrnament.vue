<template>
 <svg class="etched-ornament" :class="`etch-${kind}`" viewBox="0 0 400 400" fill="none" aria-hidden="true" focusable="false">
  <g class="etch-tracery"><path v-for="(line,i) in tracery" :key="i" :d="line"/></g>
  <g class="etch-measures"><circle cx="200" cy="200" r="167"/><circle cx="200" cy="200" r="173" stroke-dasharray="1 9"/><path v-for="n in 48" :key="n" :d="n%4===0?'M200 18V36':'M200 23V29'" :transform="`rotate(${n*7.5} 200 200)`"/></g>
  <g class="etch-inner"><path v-if="kind==='sound'" d="M170 170c-24 25-7 66 31 65 33-1 46-34 28-52-14-14-35-2-29 13 5 11 19 3 11-4M185 125v-13h30v13M185 287v-13h30v13"/><path v-else-if="kind==='type'" d="M160 140h80M200 140v120M180 260h40M151 153v-25h98v25M148 220h104M145 230v20m110-20v20"/><path v-else-if="kind==='threshold'" d="M160 266V176a40 40 0 0 1 80 0v90M170 266V176a30 30 0 0 1 60 0v90M139 266h122M200 136v130"/><path v-else d="M200 121 224 200 200 279 176 200ZM121 200h158M200 142v116"/><circle cx="200" cy="200" r="73" stroke-dasharray="27 7 1 7"/><path d="M59 59l16 16M325 325l16 16M59 341l16-16M325 75l16-16"/></g>
 </svg>
</template>
<script setup>
import {computed} from 'vue'
const props=defineProps({kind:{type:String,default:'time'}})
// Copperplate-like contours are visual ornaments, not game formulae or symbols.
const tracery=computed(()=>Array.from({length:12},(_,layer)=>Array.from({length:241},(_,i)=>{
 const a=i/240*Math.PI*2,r=104+layer*3.6
 const lobes=props.kind==='sound'?7:props.kind==='threshold'?4:props.kind==='type'?6:5
 const ripple=Math.cos(a*lobes+layer*.16)*(12+layer*.85)
 const x=200+Math.cos(a)*(r+ripple),y=200+Math.sin(a)*(r-ripple*.6)
 return `${i?'L':'M'}${x.toFixed(2)} ${y.toFixed(2)}`
}).join(' ')+'Z'))
</script>
<style scoped>
.etched-ornament{width:160px;height:160px;stroke:currentColor;stroke-width:1.1;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}.etch-tracery{opacity:.7}.etch-measures{opacity:.72}.etch-inner{opacity:.9;stroke-width:1.35}
</style>
