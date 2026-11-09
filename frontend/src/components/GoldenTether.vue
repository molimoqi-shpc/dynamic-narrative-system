<template>
 <div class="gear-tether" :class="`tether-${side}`">
  <svg viewBox="0 0 1020 48" fill="none" preserveAspectRatio="xMinYMid meet">
   <defs><linearGradient :id="metal" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#fff2bd"/><stop offset=".25" stop-color="#ad7733"/><stop offset=".48" stop-color="#f3ce77"/><stop offset=".72" stop-color="#765022"/><stop offset="1" stop-color="#d7aa57"/></linearGradient></defs>
   <g class="tether-vibration"><g class="tether-links">
    <g v-for="n in 35" :key="n" :transform="`translate(${(n-1)*30} 0)`">
     <ellipse cx="12" cy="24" rx="20" ry="11" :stroke="`url(#${metal})`" stroke-width="4"/>
     <path d="M-4 19C2 11 23 11 29 19" stroke="#fff3c0" stroke-opacity=".65" stroke-width=".8"/>
     <ellipse cx="28" cy="24" rx="14" ry="4" stroke="#e6b75e" stroke-width="3.4"/>
     <path d="M17 22H38" stroke="#ffedb5" stroke-width=".6"/>
    </g>
   </g></g>
   <path class="tether-light-run" d="M0 17H1020" stroke="#fff2bc" stroke-width="1.8" stroke-dasharray="50 1000"/>
  </svg>
  <span class="tether-shackle"/>
 </div>
</template>
<script setup>
const props=defineProps({side:String})
// This host has exactly one tether per side; a local ID avoids cross-side gradients.
const metal='tether-metal-'+props.side
</script>
