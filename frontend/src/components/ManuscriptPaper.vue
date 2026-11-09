<template>
 <svg ref="paper" class="manuscript-paper" :viewBox="geometry?`0 0 ${geometry.width} ${geometry.height}`:undefined" preserveAspectRatio="none" aria-hidden="true" focusable="false">
  <template v-if="geometry">
   <path class="manuscript-paper-shadow" :d="geometry.edge" transform="translate(1 2)" />
   <path class="manuscript-paper-page" :d="geometry.page" />
   <path class="manuscript-paper-edge" :d="geometry.edge" />
   <path class="manuscript-paper-accent" :d="geometry.accent" />
   <path class="manuscript-paper-echo" :d="geometry.echo" />
  </template>
 </svg>
</template>
<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {manuscriptGeometry} from '../utils/manuscriptGeometry'
const paper=ref(null),geometry=ref(null)
let observer,frame=0,alive=true
function measure(){
 const surface=paper.value?.parentElement,form=surface?.querySelector('.composer-form')
 if(!surface || !form)return
 const rect=surface.getBoundingClientRect(),entry=form.getBoundingClientRect()
 geometry.value=manuscriptGeometry({width:rect.width,height:rect.height,top:rect.top,formLeft:entry.left-rect.left,formTop:entry.top-rect.top,formWidth:entry.width,formHeight:entry.height})
}
function schedule(){if(!alive)return;cancelAnimationFrame(frame);frame=requestAnimationFrame(measure)}
onMounted(()=>{
 const surface=paper.value.parentElement
 if(typeof ResizeObserver!=='undefined'){
  observer=new ResizeObserver(schedule)
  for(const element of [surface,surface.querySelector('.game-composer'),surface.querySelector('.composer-form')])if(element)observer.observe(element)
 }
 window.addEventListener('resize',schedule)
 document.fonts?.ready?.then(schedule)
 measure()
})
onBeforeUnmount(()=>{alive=false;observer?.disconnect();cancelAnimationFrame(frame);window.removeEventListener('resize',schedule)})
</script>
