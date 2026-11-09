<template>
 <span ref="ribbon" class="selection-ribbon" :class="{'has-selection':visible,'ink-positioned':positioned}" :style="position" aria-hidden="true"><i class="selection-bevel"/><i class="selection-pin"/></span>
</template>
<script setup>
import {ref,watch,nextTick,onMounted,onBeforeUnmount} from 'vue'
const props=defineProps({value:[String,Number,Boolean]})
const ribbon=ref(null),visible=ref(false),positioned=ref(false),position=ref({})
let nav,observer,frame=0,alive=false
function measure(){
 if(!alive || !nav)return
 const selected=nav.querySelector('button[aria-pressed="true"],button[aria-selected="true"],[role="radio"][aria-checked="true"]')
 if(!selected){visible.value=false;return}
 const outer=nav.getBoundingClientRect(),inner=selected.getBoundingClientRect()
 if(!inner.width || !inner.height){visible.value=false;return}
 position.value={width:inner.width+'px',height:inner.height+'px',transform:`translate3d(${inner.left-outer.left+nav.scrollLeft-nav.clientLeft}px,${inner.top-outer.top+nav.scrollTop-nav.clientTop}px,0)`}
 visible.value=true
 if(!positioned.value)nextTick(()=>{if(alive)frame=requestAnimationFrame(()=>{positioned.value=true})})
}
function schedule(){if(!alive)return;cancelAnimationFrame(frame);frame=requestAnimationFrame(measure)}
watch(()=>props.value,async()=>{await nextTick();schedule()})
onMounted(()=>{
 alive=true;nav=ribbon.value.parentElement
 if(typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(schedule);observer.observe(nav)}
 nav.addEventListener('scroll',schedule,{passive:true});window.addEventListener('resize',schedule)
 document.fonts?.ready?.then(()=>schedule());schedule()
})
onBeforeUnmount(()=>{alive=false;cancelAnimationFrame(frame);observer?.disconnect();nav?.removeEventListener('scroll',schedule);window.removeEventListener('resize',schedule)})
</script>
