<template>
 <svg viewBox="0 0 80 80" fill="none" aria-hidden="true" class="relic-glyph"><circle cx="40" cy="40" r="31" opacity=".18" />
  <g v-if="kind==='EQUIPMENT'"><path d="M47 12 58 23 30 55 23 48Z"/><path d="m19 43 18 17M27 54l-9 12-5-5 11-10M47 12l-7 21 18-10"/></g>
  <g v-else-if="kind==='KEY_ITEM'"><path d="M18 20h44v40H18zM24 15h32v5M24 60v6h32v-6M26 29h28M26 36h16M26 43h28M26 50h16"/><circle cx="51" cy="50" r="6"/></g>
  <g v-else-if="kind==='CONSUMABLE'"><path d="M31 12h18v8l-3 2v10c0 5 12 11 12 23 0 9-8 13-18 13s-18-4-18-13c0-12 12-18 12-23V22l-3-2Z"/><path d="M26 49q14-7 28 0M30 57h12M33 12v-4h14v4"/></g>
  <g v-else-if="kind==='KEEPSAKE'"><circle cx="40" cy="37" r="20"/><circle cx="40" cy="37" r="13"/><path d="M36 17V9h8v8M40 57v12M33 67l7 5 7-5M33 38l5 5 10-13"/></g>
  <g v-else><path d="M40 10v7M40 63v7M10 40h7M63 40h7M19 19l5 5M56 56l5 5M19 61l5-5M56 24l5-5"/><circle cx="40" cy="40" r="21"/><path d="M43 22c-14 3-18 13-10 21s18 1 12-6-15 2-6 14M30 59q16-3 20-14"/></g>
 </svg>
</template>
<script setup>defineProps({kind:{type:String,default:'OTHER'}})</script>
<style scoped>.relic-glyph{width:80px;height:80px;stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round;filter:drop-shadow(0 0 9px #d7b8752b)}</style>
