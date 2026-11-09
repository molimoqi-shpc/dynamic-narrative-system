import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElLoading } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './theme-myth.css'
import './divine-theme.css'
import {installMotionPreferences} from './utils/motionPreferences'
installMotionPreferences()
const app = createApp(App)
app.use(createPinia())
app.use(router)
app.directive('loading', ElLoading.directive)

app.mount('#app')

import './ensemble-theme.css'
import './celestial-effects.css'
import './entry-theme.css'

import './paper-theatre.css'

import './refraction.css'
import './refraction-surfaces.css'
import './cinematic-hero.css'
import './folio-details.css'
import './folio-entry.css'
import './home-folio.css'
import './menu-transitions.css'
import './illuminated-edition.css'
import './engraved-utilities.css'
import './living-motion.css'
import './tactile-surfaces.css'
import './continuous-reading.css'
import './menu-choreography.css'
import './portrait-framing.css'
document.body.classList.add('refraction-theme')
