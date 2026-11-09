<template>
 <svg class="folio-seal" viewBox="0 0 160 160" fill="none" aria-hidden="true" focusable="false">
  <circle cx="80" cy="80" r="65" opacity=".25"/><circle cx="80" cy="80" r="58" stroke-dasharray="1 7" opacity=".6"/>
  <path d="M80 5V25M80 135V155M5 80H25M135 80H155M25 25l14 14M121 121l14 14M25 135l14-14M121 39l14-14" opacity=".6"/>
  <g v-if="kind==='identity'"><path d="M80 35 111 54V94L80 125 49 94V54Z"/><path d="m49 54 31 17 31-17M80 71v54M64 46l32 58M96 46l-32 58"/><circle cx="80" cy="80" r="13"/></g>
  <g v-else-if="kind==='insight'"><path d="M30 80Q80 19 130 80 80 141 30 80Z"/><circle cx="80" cy="80" r="23"/><circle cx="80" cy="80" r="8"/><path d="M80 30v19M80 111v19M36 48l14 14m60 36 14 14"/></g>
  <g v-else-if="kind==='resolve'"><path d="M80 27 114 50V90L80 132 46 90V50ZM80 27v105M46 50l68 40M114 50 46 90"/><path d="M66 55h28M63 106h34"/></g>
  <g v-else><ellipse cx="80" cy="80" rx="48" ry="23" transform="rotate(-35 80 80)"/><ellipse cx="80" cy="80" rx="48" ry="23" transform="rotate(35 80 80)"/><path d="M80 27v106M27 80h106M80 49l12 31-12 31-12-31Z"/><circle cx="80" cy="80" r="4"/></g>
 </svg>
</template>
<script setup>defineProps({kind:{type:String,default:'world'}})</script>
<style scoped>.folio-seal{width:120px;height:120px;stroke:currentColor;stroke-width:.9;stroke-linecap:round;stroke-linejoin:round}</style>
