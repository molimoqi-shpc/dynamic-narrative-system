<template>
  <ElConfigProvider :locale="zhCn">
    <RouteScene />
    <CharacterPanel />
    <MusicPlayer />
    <MenuTransition />
  </ElConfigProvider>
</template>
<script setup>
import CharacterPanel from './components/CharacterPanel.vue'
import MusicPlayer from './components/MusicPlayer.vue'
import MenuTransition from './components/MenuTransition.vue'
import RouteScene from './components/RouteScene.vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>
