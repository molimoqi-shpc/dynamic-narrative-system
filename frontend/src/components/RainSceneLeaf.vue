<template>
 <div class="rain-drag-rig">
  <div class="pull-sheet rainy-scene-leaf">
   <img class="rain-mural" src="/art/menu-transitions/rain-window-v2.png" alt=""/>
   <div class="rain-glass-light"/>
   <div class="rain-curtain">
    <i v-for="n in 72" :key="n" class="rain-streak" :class="n%4?'rain-far':'rain-near'" :style="{'--rain-x':(n*37%103)+'%','--rain-delay':-(n*.113)+'s','--rain-time':(.65+n%7*.14)+'s','--rain-length':(18+n%4*16)+'px'}"/>
   </div>
   <div class="rain-water-trails"><i v-for="n in 16" :key="n" :style="{'--drop-x':(n*23%96)+'%','--drop-y':(n*19%64)+'%','--drop-delay':(-n*.37)+'s','--drop-time':(1.4+n%5*.24)+'s'}"/></div>
   <div class="rain-leaf-ripples"><span v-for="n in 7" :key="n" :style="{left:(8+n*13)+'%',bottom:(5+n%3*7)+'%','--ripple-delay':(-n*.29)+'s'}"/></div>
   <div class="rain-leaf-grain"/><div class="sheet-curl rain-fold"/>
   <span class="rain-leaf-inscription">雨声入卷<small>片语留痕 / 创作札记</small></span>
  </div>
  <div class="pull-corner rain-grip-corner"/>
  <span class="rain-grip-shadow"/>
  <img class="pull-hand" src="/art/menu-transitions/hand-anime.png" alt=""/>
  <span class="rain-finger-contact"/>
 </div>
</template>
