<template>
 <section v-if="progress?.reason" class="composer-feedback story-decision-note" role="status">
  <p>{{progress.requiresInput?(current?'此刻需要你表态：':'当时的选择：'):'这一段的进展：'}}{{progress.reason}}</p>
  <p v-if="progress.requiresInput && progress.stakes" class="decision-stakes">{{progress.stakes}}</p>
 </section>
</template>
<script setup>
defineProps({progress:Object,current:{type:Boolean,default:true}})
</script>
<style scoped>
.story-decision-note p{margin:.25rem 0;line-height:1.8}.decision-stakes{color:#c8ba98}
</style>
