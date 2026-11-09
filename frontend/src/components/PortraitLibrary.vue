<template>
 <el-dialog :model-value="open" fullscreen class="portrait-library-dialog" title="人物图鉴" :show-close="false" @update:model-value="$emit('update:open',$event)" @closed="reset">
  <template #header>
   <header class="atlas-header"><div><p class="atlas-kicker">PORTRAIT ATLAS / 众生留影</p><h2>人物图鉴<span>{{portraitLibrary.length}} 幅馆藏</span></h2></div><button class="atlas-close" type="button" aria-label="关闭图鉴" @click="$emit('update:open',false)">返回主页 <span aria-hidden="true">×</span></button></header>
  </template>
  <section class="portrait-atlas" aria-label="立绘藏馆">
   <form class="atlas-filters" role="search" @submit.prevent>
    <label class="atlas-search"><span aria-hidden="true">⌕</span><input v-model="query" type="search" aria-label="搜索立绘" placeholder="搜索名称、编号或标签…"/><kbd>/ 藏目</kbd></label>
    <label class="atlas-select">题材<select v-model="culture" aria-label="筛选题材"><option value="">全部题材</option><option value="WESTERN">西幻</option><option value="EASTERN">中幻</option><option value="MODERN">现代</option></select></label>
    <label class="atlas-select">人物<select v-model="gender" aria-label="筛选性别"><option value="">全部人物</option><option value="female">女性</option><option value="male">男性</option><option value="unknown">未限定</option></select></label>
    <button v-if="query || culture || gender" type="button" class="atlas-reset" @click="reset">重置筛选</button><output class="atlas-count" aria-live="polite">{{filtered.length}} / {{portraitLibrary.length}} 幅</output>
   </form>
   <div v-if="filtered.length" class="atlas-layout">
    <section class="atlas-directory" aria-label="立绘目录"><div class="atlas-directory-grid">
     <button v-for="asset in filtered" :key="asset.key" type="button" class="atlas-card" :class="{'is-selected':selected.key===asset.key}" :aria-pressed="selected.key===asset.key" :aria-label="`查看${asset.label}`" @click="selectedKey=asset.key">
      <span class="atlas-card-art"><img :src="asset.src" :alt="asset.label" loading="lazy" decoding="async"/><span class="atlas-edition" v-if="asset.archived">旧版留存</span></span><span class="atlas-card-name">{{asset.label}}</span><small>{{asset.key}}</small>
     </button>
    </div></section>
    <section class="atlas-artwork" aria-label="完整立绘"><div class="atlas-artwork-mark" aria-hidden="true">{{String(portraitLibrary.indexOf(selected)+1).padStart(2,'0')}}</div>
     <el-image :key="selected.src" class="atlas-full-image" :src="selected.src" :alt="selected.label+'完整立绘'" fit="contain" :preview-src-list="[selected.src]" preview-teleported hide-on-click-modal>
      <template #error><div class="atlas-image-error">图片暂未加载成功。<a :href="selected.src" target="_blank" rel="noopener">打开原图重试 ↗</a></div></template>
     </el-image>
     <div class="atlas-artwork-footer"><button type="button" aria-label="上一张立绘" :disabled="filtered.length<2" @click="move(-1)">←</button><span>完整画幅 · 点击图片放大</span><button type="button" aria-label="下一张立绘" :disabled="filtered.length<2" @click="move(1)">→</button></div>
    </section>
    <aside class="atlas-notes" aria-label="立绘标签"><p class="atlas-kicker">藏品 / {{String(portraitLibrary.indexOf(selected)+1).padStart(2,'0')}}</p><h3>{{selected.label}}</h3><code>{{selected.key}}</code><p class="atlas-edition-note">{{selected.archived?'旧版留存 · 保留原始画幅':selected.approved?'已收录立绘':'基础立绘'}}<a :href="selected.src" target="_blank" rel="noopener">查看原图 ↗</a></p>
     <section class="atlas-tag-section"><h4>人物分类</h4><div class="atlas-tags"><span v-for="tag in selected.tags" :key="tag">{{tag}}</span></div></section>
     <section v-for="group in selected.groups" :key="group.label" class="atlas-tag-section"><h4>{{group.label}}</h4><div class="atlas-tags"><span v-for="tag in group.values" :key="tag">{{tag}}</span></div></section>
     <p class="atlas-footnote">这里记载的是立绘素材与适用标签。故事中的人物身份与经历，以各自存档为准。</p>
    </aside>
   </div>
   <div v-else class="atlas-empty"><span aria-hidden="true">◇</span><h3>暂未寻到这一幅。</h3><p>试试其他名称、编号或特征。</p><button type="button" @click="reset">查看全部立绘</button></div>
  </section>
 </el-dialog>
</template>
<script setup>
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {ElDialog,ElImage} from 'element-plus'
import {portraitLibrary,filterPortraitLibrary} from '../utils/portraitLibrary'
const props=defineProps({open:Boolean})
defineEmits(['update:open'])
const query=ref(''),culture=ref(''),gender=ref(''),selectedKey=ref('')
const filtered=computed(()=>filterPortraitLibrary({query:query.value,culture:culture.value,gender:gender.value}))
const selected=computed(()=>filtered.value.find(asset=>asset.key===selectedKey.value) || filtered.value[0])
function reset(){query.value='';culture.value='';gender.value='';selectedKey.value=''}
function move(delta){const count=filtered.value.length;if(count<2)return;const index=filtered.value.indexOf(selected.value);selectedKey.value=filtered.value[(index+delta+count)%count].key}
function keydown(event){
 if(!props.open || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.target?.closest?.('input,select,textarea,[contenteditable="true"],.el-image-viewer__wrapper'))return
 if(event.key==='ArrowLeft' || event.key==='ArrowRight'){event.preventDefault();move(event.key==='ArrowLeft'?-1:1)}
}
onMounted(()=>window.addEventListener('keydown',keydown))
onBeforeUnmount(()=>window.removeEventListener('keydown',keydown))
</script>
<style>
.portrait-library-dialog.el-dialog{padding:24px clamp(18px,3vw,48px);background:#f0f1ed;color:#263947;overflow:hidden;display:flex;flex-direction:column;--el-color-primary:#305a7d;--el-text-color-regular:#263947;--el-bg-color-overlay:#f0f1ed}
.portrait-library-dialog .el-dialog__header{padding:0;margin:0;flex:none}.portrait-library-dialog .el-dialog__body{padding:0;min-height:0;flex:1;display:flex}
</style>
<style scoped>
.atlas-header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:0 0 20px;border-bottom:1px solid #b9c7cc}.atlas-kicker{margin:0 0 9px;color:#687c8b;letter-spacing:2px;font-size:10px}.atlas-header h2{margin:0;font-family:var(--reading-font,serif);font-size:30px;letter-spacing:4px;font-weight:500}.atlas-header h2 span{font:11px sans-serif;letter-spacing:1px;color:#788891;margin-left:20px}.atlas-close{border:1px solid #c0cbd0;padding:9px 12px;background:transparent;color:#344f64;cursor:pointer;display:flex;align-items:center;gap:16px}.atlas-close span{font-size:23px;line-height:1}
.portrait-atlas{display:flex;flex-direction:column;flex:1;min-width:0;min-height:0}.atlas-filters{display:flex;gap:20px;align-items:center;padding:18px 0;flex:none;flex-wrap:wrap}.atlas-search{display:flex;align-items:center;gap:12px;border-bottom:1px solid #849bad;flex:1;min-width:210px;padding:8px 0}.atlas-search>span{font-size:25px;line-height:1}.atlas-search input{border:0;outline:none;background:transparent;color:inherit;font:13px sans-serif;width:100%;min-width:0}.atlas-search kbd{white-space:nowrap;font:10px sans-serif;color:#82919a}.atlas-select{display:flex;align-items:center;gap:9px;color:#788992;font-size:11px}.atlas-select select{font:12px sans-serif;background:transparent;border:0;border-bottom:1px solid #b8c6ce;padding:7px 18px 7px 0;color:#29445b}.atlas-reset{border:0;background:transparent;color:#305a7d;font-size:12px;cursor:pointer}.atlas-count{font:11px sans-serif;color:#71828d;white-space:nowrap;font-variant-numeric:tabular-nums}
.atlas-layout{display:grid;grid-template-columns:minmax(230px,.8fr) minmax(260px,1.2fr) minmax(235px,.78fr);gap:clamp(16px,2vw,30px);flex:1;min-height:0}.atlas-directory{overflow:auto;padding:0 9px 24px 0;scrollbar-width:thin;scrollbar-color:#acbec9 transparent}.atlas-directory-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:15px 12px}.atlas-card{font:inherit;text-align:left;padding:0 0 9px;background:transparent;border:1px solid transparent;border-bottom-color:#ccd3d4;color:inherit;cursor:pointer;min-width:0;transition:border-color .2s,background .2s}.atlas-card-art{position:relative;display:block;height:clamp(135px,19vh,210px);background:linear-gradient(140deg,#dce1e0,#e6e9e4 65%,#d1dce1);overflow:hidden}.atlas-card-art img{height:100%;width:100%;max-width:100%;object-fit:contain;object-position:center;transition:transform .25s}.atlas-card:hover .atlas-card-art img{transform:translateY(-3px)}.atlas-card:hover,.atlas-card.is-selected{border-color:#476c85;background:#e0e7e7}.atlas-card.is-selected .atlas-card-name{color:#174c70}.atlas-card-name{display:block;font-size:12px;margin:10px 7px 5px;line-height:1.5}.atlas-card small{display:block;font:8px sans-serif;color:#7b8b94;overflow-wrap:anywhere;margin:0 7px}.atlas-edition{position:absolute;left:6px;top:6px;font-size:8px;padding:3px 5px;color:#5c6f7a;background:#f3f4efe6}
.atlas-artwork{isolation:isolate;position:relative;min-height:0;display:flex;flex-direction:column;background:radial-gradient(ellipse at 50% 42%,#4c5b67 0,#263845 50%,#182a38 100%);border:1px solid #9cabb5;overflow:hidden}.atlas-artwork::before{content:'';position:absolute;inset:12px;border:1px solid #c8d4d121;pointer-events:none;z-index:1}.atlas-artwork::after{content:'';position:absolute;inset:18% 3%;border:1px solid #b7c9cc25;border-radius:50%;transform:rotate(-20deg);pointer-events:none;z-index:-1}.atlas-artwork-mark{position:absolute;right:20px;top:12px;color:#dde6df12;font:100px Georgia,serif;pointer-events:none}.atlas-full-image{width:100%;min-height:0;flex:1;cursor:zoom-in}.atlas-full-image :deep(img){object-fit:contain!important;object-position:center!important;width:100%;height:100%;transform:none!important;filter:drop-shadow(0 12px 18px #0003)}.atlas-artwork-footer{position:relative;display:flex;align-items:center;justify-content:space-between;padding:13px 18px;color:#c9d4d5;border-top:1px solid #91a7b329;font-size:10px;flex:none}.atlas-artwork-footer button{border:1px solid #8ea2af4d;background:transparent;color:inherit;font-size:18px;width:34px;height:30px;cursor:pointer}.atlas-artwork-footer button:disabled{opacity:.3;cursor:default}.atlas-image-error{display:flex;flex-direction:column;gap:12px;color:#d8e4e6;align-items:center;justify-content:center;height:100%;font-size:12px}.atlas-image-error a{color:inherit}
.atlas-notes{overflow:auto;min-width:0;padding:5px 9px 30px 0;scrollbar-width:thin;scrollbar-color:#acbec9 transparent}.atlas-notes h3{margin:9px 0 12px;font:500 clamp(21px,2vw,30px)/1.5 serif;letter-spacing:2px}.atlas-notes code{font:10px/1.6 monospace;color:#81909a;overflow-wrap:anywhere}.atlas-edition-note{display:flex;gap:12px;justify-content:space-between;margin:20px 0;font-size:10px;color:#798b93}.atlas-edition-note a{color:#3e6179;text-decoration:none;white-space:nowrap}.atlas-tag-section{padding:17px 0 5px;border-top:1px solid #bdc9cd}.atlas-tag-section h4{font-size:11px;color:#5b7487;font-weight:500;letter-spacing:2px;margin:0 0 12px}.atlas-tags{display:flex;flex-wrap:wrap;gap:7px}.atlas-tags span{font-size:11px;line-height:1.6;padding:4px 7px;background:#e3e8e6;color:#526775;border:1px solid #ccd5d6}.atlas-footnote{font-size:10px;line-height:1.9;color:#84939b;margin:24px 0}.atlas-empty{margin:auto;text-align:center;padding:60px 20px;color:#708493}.atlas-empty>span{font-size:50px;color:#a1b4c1}.atlas-empty h3{font:24px serif;color:#3d5a70}.atlas-empty p{font-size:12px}.atlas-empty button{padding:10px 18px;border:1px solid #aebec9;background:none;color:#3d5a70;cursor:pointer}
button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid #3a78a7;outline-offset:3px}
@media(min-width:1600px){.atlas-directory-grid{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:950px){.atlas-layout{grid-template-columns:minmax(160px,.55fr) minmax(220px,1fr);overflow:auto;align-content:start}.atlas-directory{grid-column:1;grid-row:1/3;max-height:100%;position:sticky;top:0}.atlas-directory-grid{grid-template-columns:1fr}.atlas-artwork{grid-column:2;height:62vh;min-height:360px}.atlas-notes{grid-column:2;overflow:visible}.atlas-filters{gap:12px}.atlas-header h2{font-size:24px}.atlas-header h2 span{font-size:10px;margin-left:12px}}
@media(max-width:550px){.atlas-header{padding-bottom:14px}.atlas-header h2 span{display:none}.atlas-close{font-size:11px;gap:10px;padding:7px}.atlas-search{flex-basis:100%}.atlas-search kbd{display:none}.atlas-filters{padding:12px 0}.atlas-layout{grid-template-columns:82px minmax(0,1fr);gap:12px}.atlas-card-art{height:115px}.atlas-card-name{font-size:10px;margin:5px 4px}.atlas-card small{font-size:7px;margin:0 4px}.atlas-directory{padding-right:2px}.atlas-directory-grid{gap:10px}.atlas-edition{font-size:7px;left:2px;padding:2px}.atlas-artwork{min-height:300px;height:52vh}.atlas-artwork-footer{padding:10px 8px;gap:7px}.atlas-artwork-footer>span{font-size:8px}.atlas-artwork-footer button{width:26px}.atlas-notes h3{font-size:22px}.atlas-edition-note{flex-wrap:wrap}}
@media(prefers-reduced-motion:reduce){.atlas-card,.atlas-card-art img{transition:none}.atlas-card:hover .atlas-card-art img{transform:none}}
</style>
