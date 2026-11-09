<template>
 <div class="lake-depths">
  <img class="lake-water-surface" src="/art/menu-transitions/lake-water-v2.png" alt=""/>
  <div class="lake-phantoms">
   <div v-for="(spirit,i) in spirits" :key="i" class="lake-spirit" :class="`spirit-${i}`">
    <div class="spirit-travel"><img :src="spirit" alt=""/><div class="spirit-wake"><i v-for="n in 3" :key="n" :style="{'--wake-delay':(-n*.42)+'s'}"/></div></div>
   </div>
  </div>
  <svg class="lake-refractions" viewBox="0 0 1440 900" preserveAspectRatio="none" fill="none"><path v-for="n in 6" :key="n" :d="`M-100 ${n*150}C260 ${n*125-100} 450 ${n*150+80} 770 ${n*135}S1180 ${n*155-65} 1540 ${n*140}`"/></svg>
  <span v-for="n in 8" :key="n" class="lake-glint" :style="{left:(n*29%93)+'%',top:(n*17%82)+'%','--glint-delay':(-n*.29)+'s'}"/>
 </div>
</template>
<script setup>
const spirits=['/art/menu-transitions/lake-wraith-v2.png','/art/menu-transitions/lake-shade-v2.png','/art/menu-transitions/lake-wraith-v2.png']
</script>
