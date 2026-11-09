<template>
 <RouterView v-slot="{Component,route}"><component :is="Component" v-folio-motion="route.path" /></RouterView>
</template>
<script setup>
import {RouterView} from 'vue-router'
import {folioMotion as vFolioMotion} from '../utils/folioMotion'
</script>
