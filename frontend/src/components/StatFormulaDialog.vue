<template>
  <el-dialog :model-value="modelValue" @update:model-value="emit('update:modelValue',$event)" title="六维说明与公式" width="820px" align-center class="stat-formula-dialog engraved-formula">
    <div class="formula-scroll">
      <div class="formula-frontispiece"><EtchedOrnament kind="time"/><div><p class="utility-kicker">万象相生 / 六维解析</p><p class="formula-version">{{book?.title || '六维计算说明'}} · {{book?.version || '等待数据'}}</p></div></div>
      <nav class="formula-tabs selection-track" aria-label="六维公式切换"><SelectionRibbon :value="selected"/>
        <button v-for="stat in statDefinitions" :key="stat.key" type="button" :aria-pressed="selected===stat.key" @click="selected=stat.key">{{stat.label}}</button>
      </nav>
      <section v-if="entry" v-folio-motion="selected" class="formula-selected" :aria-label="`${entry.label}的公式与当前数值`">
        <header class="formula-identity"><span>{{entry.label}}</span><div><small>此维所长</small><h3>{{entry.description}}</h3></div></header>
        <p class="formula-expression">{{entry.formula}}</p>
        <div class="formula-values">
          <div><small>基础值</small><strong>{{entry.base}}</strong></div>
          <div><small>成长曲线 C</small><strong>{{number(entry.growthValue)}}</strong></div>
          <div><small>根基系数 M</small><strong>{{number(entry.masteryMultiplier)}}</strong></div>
          <div><small>协同系数</small><strong>{{number(entry.synergyMultiplier)}}</strong></div>
          <div><small>最终实际值</small><strong>{{entry.actual}}</strong></div>
        </div>
        <p class="formula-example">当前计算：{{number(entry.growthValue)}} × {{number(entry.masteryMultiplier)}} × {{number(entry.synergyMultiplier)}} = {{number(entry.unrounded)}} → 四舍五入得 {{entry.actual}}。</p>
        <p class="formula-note">辅助属性：{{entry.supportNames?.[0]}} {{entry.supportBases?.[0]}}、{{entry.supportNames?.[1]}} {{entry.supportBases?.[1]}}；S={{number(entry.synergy)}}。中间数仅为显示保留四位小数，服务器使用完整精度。</p>
      </section>
      <p v-else role="status">当前服务尚未提供公式明细，请关闭说明并重新加载场景。</p>
      <details v-if="book?.common?.length" open class="formula-rules">
        <summary>通用符号与成长曲线</summary>
        <p v-for="rule in book.common" :key="rule">{{rule}}</p>
      </details>
      <details v-if="book?.checks?.length" class="formula-rules">
        <summary>六维如何影响对抗、干预和打听</summary>
        <p v-for="rule in book.checks" :key="rule">{{rule}}</p>
      </details>
      <p class="formula-note">公式与当前数值由服务器提供，与结算使用相同版本。复杂公式是本项目的定制设计，平衡仍需持续结合游玩反馈调整。</p>
      <details v-if="book?.references?.length" class="formula-rules">
        <summary>设计参考</summary>
        <p>借鉴能力分工、检定修正与技能协作。上述公式和系数为本项目定制，并非照搬这些游戏的数值。</p>
        <p v-for="source in book.references" :key="source.url"><a :href="source.url" target="_blank" rel="noopener noreferrer">{{source.title}}</a></p>
      </details>
    </div>
  </el-dialog>
</template>

<script setup>
import SelectionRibbon from './SelectionRibbon.vue'
import {folioMotion as vFolioMotion} from '../utils/folioMotion'
import {computed,ref,watch} from 'vue'
import {ElDialog} from 'element-plus'
import {statDefinitions} from '../utils/sceneGuidance'
import EtchedOrnament from './EtchedOrnament.vue'
const props=defineProps({modelValue:Boolean,book:Object,initialKey:{type:String,default:'zhi'}})
const emit=defineEmits(['update:modelValue'])
const selected=ref(props.initialKey)
watch(()=>[props.modelValue,props.initialKey],([open,key])=>{if(open) selected.value=key})
const entry=computed(()=>props.book?.stats?.find(stat=>stat.key===selected.value))
const number=value=>Number.isFinite(value)?value.toFixed(4):'—'
</script>
