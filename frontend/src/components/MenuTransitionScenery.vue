<template>
 <div class="transition-scenery" :class="`scenery-${kind}`" aria-hidden="true">
  <template v-if="kind==='angel'">
   <svg class="angel-architecture" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" fill="none">
    <defs><linearGradient id="arch-ink" x2="0" y2="1"><stop stop-color="#d8e7ff" stop-opacity=".65"/><stop offset="1" stop-color="#8295b4" stop-opacity=".08"/></linearGradient></defs>
    <g stroke="url(#arch-ink)" stroke-width="1">
     <path v-for="(r,i) in [510,440,375,325]" :key="r" :d="`M${720-r} 920V420A${r} ${r*.82} 0 0 1 ${720+r} 420V920`" :class="`arch-layer arch-layer-${i}`"/>
     <path d="M85 900V340Q85 130 280 0M1355 900V340Q1355 130 1160 0M235 900V460M1205 900V460M0 770H1440M0 790H1440M300 900 720 510 1140 900M100 900 720 510 1340 900M720 510V900"/>
     <path d="M200 420H330M1110 420H1240M240 440H305M1135 440H1200M665 130H775M720 82V184"/>
    </g>
    <g stroke="#e5edfa" stroke-opacity=".3"><circle cx="720" cy="375" r="92"/><circle cx="720" cy="375" r="100" stroke-dasharray="1 14"/><path d="M600 375H840M720 255V495"/></g>
   </svg>
   <div class="angel-light-plane light-plane-one"/><div class="angel-light-plane light-plane-two"/>
   <svg v-for="n in 9" :key="n" class="feather-trace" viewBox="0 0 28 80" :style="{'--feather-x':(8+n*8.8)+'%','--feather-y':(18+n%3*19)+'%','--feather-delay':(n*.05)+'s','--feather-angle':(-45+n*11)+'deg'}"><path d="M13 74C0 58-3 25 23 3c8 21-2 53-10 71ZM13 74 23 3M14 55 4 43M17 40 8 27M19 30 26 22"/></svg>
   <span class="scenery-index">I / 初见</span><span class="scenery-note">万象未书 · 此生将启</span>
  </template>
  <template v-else-if="kind==='gears'">
   <svg class="mechanism-blueprint" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" fill="none" stroke="currentColor">
    <g stroke-width=".8"><circle cx="720" cy="430" r="345"/><circle cx="720" cy="430" r="360" stroke-dasharray="2 13"/><circle cx="720" cy="430" r="315" stroke-dasharray="90 28 4 28"/>
     <path d="M0 190H240V430H385M1440 190H1200V430H1055M0 710H240V430M1440 710H1200V430M120 0V900M1320 0V900M140 0V900M1300 0V900M720 0V85M720 775V900"/>
     <path v-for="n in 22" :key="n" :d="`M${n<12?110:1290} ${90+n%11*65}h40m-30-6v12m20-12v12`"/>
     <path d="M185 235h110v110H185zM1145 570h110v110h-110zM200 250l80 80m0-80-80 80M1160 585l80 80m0-80-80 80"/>
    </g>
   </svg>
   <svg v-for="(p,i) in [[12,24],[85,75]]" :key="i" class="satellite-gear" viewBox="0 0 100 100" :style="{left:p[0]+'%',top:p[1]+'%','--gear-direction':i?-1:1}" fill="none"><g stroke="currentColor"><circle cx="50" cy="50" r="37"/><circle cx="50" cy="50" r="25"/><path v-for="n in 16" :key="n" d="M47 7h6v9h-6z" :transform="`rotate(${n*22.5} 50 50)`"/><path d="M50 25v50M25 50h50M32 32l36 36m0-36L32 68"/></g></svg>
   <span class="scenery-index">II / 留存</span><span class="scenery-note">往事有迹 · 旧卷重续</span>
  </template>
  <template v-else-if="kind==='tide'">
   <svg class="tide-cartography" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" fill="none" stroke="currentColor">
    <path v-for="n in 7" :key="n" :d="`M-100 ${120+n*80}C220 ${n*55} 360 ${400+n*58} 680 ${200+n*65}S1120 ${n*80} 1540 ${160+n*74}`"/>
    <g transform="translate(720 420)"><ellipse rx="390" ry="190" transform="rotate(-26)"/><ellipse rx="390" ry="190" transform="rotate(26)"/><circle r="180" stroke-dasharray="2 18"/><path d="M-270 120-140-130 0 80 140-130 270 120-270 120M-140-130H140M0 80V270"/>
     <circle v-for="(p,i) in [[-270,120],[-140,-130],[0,80],[140,-130],[270,120],[0,270]]" :key="i" :cx="p[0]" :cy="p[1]" r="7"/>
    </g>
   </svg>
   <div class="tide-stat-echo"><span v-for="s in ['知','慧','斗','闻','本','归']" :key="s">{{s}}</span></div>
   <span class="scenery-index">III / 潮汐</span><span class="scenery-note">六维相生 · 命途自定</span>
  </template>
  <template v-else>
   <div class="rain-landscape"/>
   <svg class="rain-window" viewBox="0 0 1440 900" preserveAspectRatio="xMidYMid slice" fill="none" stroke="currentColor"><path d="M80 920V350A330 330 0 0 1 740 350V920M105 920V350A305 305 0 0 1 715 350V920M410 25V900M80 380H740M80 660H740M0 795H1440M0 810H1440M1030 0 870 900"/><path d="M170 900 410 660 650 900M410 660 1130 900M410 660-200 900"/></svg>
   <div class="rain-halo"/><span v-for="n in 5" :key="n" class="rain-ripple" :style="{left:(8+n*15)+'%',bottom:(4+n%3*8)+'%','--ripple-delay':(-n*.27)+'s'}"/>
   <span class="scenery-index">IV / 札记</span><span class="scenery-note">雨声入卷 · 片语留痕</span>
  </template>
 </div>
</template>
<script setup>defineProps({kind:String})</script>
