<template>
 <details ref="menu" class="journey-menu">
  <summary aria-label="存档与退出菜单" title="存档与退出"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 4H4v16h6M9 12h12m-5-5 5 5-5 5" /></svg></summary>
  <section class="journey-menu-panel" aria-label="旅程菜单"><p>暂别此间</p><button @click="leave(true)">切换存档 <span>↗</span></button><button @click="leave(false)">返回主菜单 <span>↗</span></button><button @click="close">继续阅读 <span>×</span></button><small>{{pending?'当前生成仍由服务器处理，回来后可查询原操作。':'已经发表的故事会自动留存。'}}返回菜单不会清空当前存档。</small></section>
 </details>
</template>
<script setup>
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {useRouter} from 'vue-router'
defineProps({pending:Boolean})
const menu=ref(null),router=useRouter()
function close(){if(menu.value)menu.value.open=false}
function leave(archives){close();router.push(archives?'/home?archives=1':'/home')}
function outside(event){if(!menu.value?.contains(event.target))close()}
function key(event){if(event.key==='Escape')close()}
onMounted(()=>{document.addEventListener('pointerdown',outside);document.addEventListener('keydown',key)})
onBeforeUnmount(()=>{document.removeEventListener('pointerdown',outside);document.removeEventListener('keydown',key)})
</script>
