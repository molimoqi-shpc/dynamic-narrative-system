import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../store/session'
import http from '../api'
import LoginView from '../views/LoginView.vue'
import HomeView  from '../views/HomeView.vue'
const PlayView = () => import('../views/PlayView.vue')

// 动态 import：Step 2 / Step 6 实施后才真正加载，dev 模式下文件不存在时控制台告警但应用不崩溃
const CharacterCreationView = () => import('../views/CharacterCreationView.vue')
const EngineDemoView       = () => import('../views/EngineDemoView.vue')

const routes = [
  ...(import.meta.env.DEV?[{path:'/verification-play',name:'verification-play',component:()=>import('../views/FeedbackVerificationView.vue')}]:[]),
  { path: '/',            redirect: '/home' },
  { path: '/theatre-review.html', redirect: '/home' },
  { path: '/login',       name: 'login',       component: LoginView },
  { path: '/home',        name: 'home',        component: HomeView,  meta: { requiresAuth: true } },
  { path: '/new-game',    name: 'new-game',    component: CharacterCreationView, meta: { requiresAuth: true } },
  { path: '/play',        name: 'play',        component: PlayView,  meta: { requiresAuth: true, requiresActiveSession: true } },
  { path: '/engine-demo', name: 'engine-demo', component: EngineDemoView,       meta: { requiresAuth: true } },
  { path: '/about', name: 'about', component: () => import('../views/AboutView.vue'), meta: { requiresAuth: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to,from,savedPosition){return savedPosition || (to.path!==from.path?{left:0,top:0}:undefined)}
})

router.beforeEach(async (to, _from, next) => {
  const token = localStorage.getItem('bs_token')
  if (to.meta.requiresAuth && !token) return next({ name: 'login' })

  if(to.meta.requiresAuth) {
    const session=useSessionStore()
    if(!session.playerId) {
      const generation=session.sessionGeneration
      const ownsAccountRequest=()=>token===localStorage.getItem('bs_token') && generation===session.sessionGeneration
      try {
        const account=await http.get('/auth/me',{timeout:15000})
        if(!ownsAccountRequest())return next(false)
        if(!account?.playerId)throw new Error('Account verification is incomplete')
        session.setActiveSession({playerId:account.playerId})
      } catch(e) {
        if(!ownsAccountRequest())return next(false)
        if([401,403].includes(e.response?.status)){
          localStorage.removeItem('bs_token');session.clearSession()
          return next({name:'login'})
        }
        // A lost connection is not an expired login. Keep the token and saved
        // selection, and offer an explicit retry instead of a blank initial route.
        return next({name:'login',query:{reconnect:'1',returnTo:to.fullPath}})
      }
    }
  }

  // /play 守卫：要求 activeSession（sessionId 非空 + status==='PLAYING'）
  if (to.meta.requiresActiveSession) {
    const session = useSessionStore()
    if(!session.canPlay && session.rememberedSessionId()) {
      const playerId=session.playerId,generation=session.sessionGeneration,selectedSessionId=session.rememberedSessionId()
      const ownsResumeRequest=()=>token===localStorage.getItem('bs_token') && playerId===session.playerId && generation===session.sessionGeneration && selectedSessionId===session.rememberedSessionId()
      try {
        const restored=await http.post('/game/resume',{sessionId:selectedSessionId,playerId},{timeout:15000})
        if(!ownsResumeRequest())return next(false)
        session.setActiveSession({...restored,playerId})
      } catch(e) {
        if(!ownsResumeRequest())return next(false)
        // Invalid or foreign selections are discarded; transient failures do not erase server saves.
        if([400,403,404].includes(e.response?.status))session.forgetSelectedSession()
      }
    }
    if (!session.canPlay) {
      return next({ name: 'home' })
    }
  }
  next()
})

export default router
