<template>
 <div class="seraph-stage">
  <div class="seraph-halo"><svg viewBox="0 0 300 300" fill="none"><circle cx="150" cy="150" r="137"/><circle cx="150" cy="150" r="128" stroke-dasharray="1 9"/><path v-for="n in 12" :key="n" d="M150 7v17m-4-10h8" :transform="`rotate(${n*30} 150 150)`"/></svg></div>
  <div v-for="(pair,i) in pairs" :key="i" class="seraph-wing-pair" :class="`seraph-pair-${i}`" :style="{'--pair-scale':pair.scale,'--pair-angle':pair.angle+'deg','--pair-delay':pair.delay+'ms'}">
   <div class="seraph-wing seraph-wing-left"><img :src="wings" alt=""/></div>
   <div class="seraph-wing seraph-wing-right"><img :src="wings" alt=""/></div>
  </div>
  <img class="seraph-core" :src="body" alt=""/>
  <span class="seraph-breath"/>
 </div>
</template>
<script setup>
const wings='/art/menu-transitions/seraph-wings-v2.png',body='/art/menu-transitions/seraph-body-v2.png'
const pairs=[{scale:.83,angle:-28,delay:100},{scale:.70,angle:-5,delay:380},{scale:.53,angle:18,delay:680}]
</script>
