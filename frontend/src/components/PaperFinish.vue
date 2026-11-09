<template>
 <span class="paper-finish" aria-hidden="true"><span class="paper-glaze"/><svg class="paper-fold" viewBox="0 0 64 64" focusable="false"><defs><linearGradient :id="foldId" x1="0" y1="1" x2="1" y2="0"><stop stop-color="#8da5b8"/><stop offset=".44" stop-color="#d8e2e8"/><stop offset=".64" stop-color="#fffef7"/><stop offset="1" stop-color="#c3d0da"/></linearGradient></defs><path class="fold-void" d="M0 0H64V64Z"/><path class="fold-shadow" d="M0 2 64 64H0Z"/><path class="fold-leaf" d="M0 0 60 60H0Z" :fill="`url(#${foldId})`"/><path class="fold-edge" d="M1 2V59H58"/></svg></span>
</template>
<script setup>
const foldId='paper-fold-'+Math.random().toString(36).slice(2,10)
</script>
