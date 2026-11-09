<template>
  <canvas ref="canvas" class="ambient-particles" aria-hidden="true" />
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import {currentMotion,MOTION_EVENT} from '../utils/motionPreferences'
const canvas = ref(null)
let ctx, frame = 0, width = 0, height = 0, last = 0, points = [], ripples = [], media
let running = false, reduced = false, coarse = false
const pointer = { x: -1000, y: -1000, active: false }
const colors = ['#e5eaf3', '#c7d7ef', '#dfc9a1']
const sprites = []
function resize() {
  width = innerWidth; height = innerHeight
  const ratio = Math.min(devicePixelRatio || 1, 1.5)
  canvas.value.width = Math.round(width * ratio); canvas.value.height = Math.round(height * ratio)
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  const count = Math.min(coarse ? 100 : 280, Math.round(width * height / 6500))
  points = Array.from({ length: count }, () => ({
    bx: Math.random() * width, by: Math.random() * height, x: 0, y: 0, vx: 0, vy: 0,
    phase: Math.random() * Math.PI * 2, size: 1 + Math.random() * 1.7,
    alpha: .25 + Math.random() * .5, color: Math.floor(Math.random() * colors.length)
  }))
  ripples = []; draw(0, 0)
}
function draw(time, dt) {
  ctx.clearRect(0, 0, width, height)
  const step = Math.min(dt * 30, 1.8)
  const now = time / 1000
  ripples = ripples.filter(r => now - r.start < 2.4)
  for (const p of points) {
    const driftX = reduced ? 0 : Math.sin(now * .17 + p.phase) * 12
    const driftY = reduced ? 0 : Math.cos(now * .13 + p.phase) * 9
    let fx = -p.x * .018, fy = -p.y * .018
    const x = p.bx + driftX + p.x, y = p.by + driftY + p.y
    if (pointer.active && !coarse && !reduced) {
      const dx = x - pointer.x, dy = y - pointer.y, d = Math.hypot(dx, dy)
      if (d > .01 && d < 100) { const force = (1 - d / 100) * .9; fx += dx / d * force; fy += dy / d * force }
    }
    if (!reduced) for (const r of ripples) {
      const age = now - r.start, radius = age * 115
      const dx = x - r.x, dy = y - r.y, d = Math.hypot(dx, dy)
      const band = Math.abs(d - radius)
      if (d > .01 && band < 22) { const force = (1 - band / 22) * (1 - age / 2.4) * 1.1; fx += dx / d * force; fy += dy / d * force }
    }
    p.vx = (p.vx + fx * step) * Math.pow(.91, step); p.vy = (p.vy + fy * step) * Math.pow(.91, step)
    p.x += p.vx * step; p.y += p.vy * step
    // Keep the central reading area quiet; particles are stronger at the edges.
    const center = Math.abs(x / width - .5) < .3 && y / height > .13 && y / height < .85
    ctx.globalAlpha = p.alpha * (center ? .28 : .75) * (reduced ? 1 : .78 + Math.sin(now * .4 + p.phase) * .22)
    const size = p.size * 5
    ctx.drawImage(sprites[p.color], x - size / 2, y - size / 2, size, size)
  }
  ctx.globalAlpha = 1
}
function animate(time) {
  if (!running) return
  // Cap drawing at approximately 30fps; motion uses elapsed time rather than frame count.
  if (time - last >= 32) { const dt = last ? Math.min((time - last) / 1000, .06) : 0; draw(time, dt); last = time }
  frame = requestAnimationFrame(animate)
}
function syncMotion() {
  cancelAnimationFrame(frame); frame = 0; running = false; last = 0
  reduced = media.matches || currentMotion()!=='FULL'
  canvas.value.dataset.motion = document.hidden ? 'paused' : reduced ? 'reduced' : 'active'
  if (document.hidden) { pointer.active = false; ripples = []; return }
  if (reduced) { pointer.active = false; ripples = []; draw(0, 0); return }
  running = true; frame = requestAnimationFrame(animate)
}
function move(event) {
  if (event.pointerType === 'touch' || reduced || document.hidden) return
  pointer.x = event.clientX; pointer.y = event.clientY; pointer.active = true
}
function leave() { pointer.active = false }
function press(event) {
  if (reduced || document.hidden || event.button !== 0) return
  // Actions, form controls, panel buttons and music controls never trigger decorative ripples.
  if (event.target instanceof Element && event.target.closest('button,a,input,textarea,select,label,summary,[role="button"],[contenteditable="true"],.el-dialog,.el-drawer,.celestial-title')) return
  ripples.push({ x: event.clientX, y: event.clientY, start: performance.now() / 1000 })
  if (ripples.length > 4) ripples.shift()
}
onMounted(() => {
  try { ctx = canvas.value.getContext('2d', { alpha: true }) } catch { return }
  if (!ctx) return
  coarse = matchMedia('(pointer: coarse)').matches
  media = matchMedia('(prefers-reduced-motion: reduce)')
  for (const color of colors) {
    const sprite = document.createElement('canvas'); sprite.width = sprite.height = 24
    const s = sprite.getContext('2d'); const gradient = s.createRadialGradient(12, 12, 0, 12, 12, 12)
    gradient.addColorStop(0, color); gradient.addColorStop(.18, color + 'b0'); gradient.addColorStop(1, color + '00')
    s.fillStyle = gradient; s.fillRect(0, 0, 24, 24); sprites.push(sprite)
  }
  resize(); syncMotion()
  window.addEventListener('resize', resize)
  window.addEventListener('pointermove', move, { passive: true })
  window.addEventListener('pointerdown', press, { passive: true })
  document.addEventListener('pointerleave', leave)
  window.addEventListener('blur', leave)
  document.addEventListener('visibilitychange', syncMotion)
  media.addEventListener('change', syncMotion)
  window.addEventListener(MOTION_EVENT,syncMotion)
})
onBeforeUnmount(() => {
  running = false; cancelAnimationFrame(frame)
  window.removeEventListener('resize', resize); window.removeEventListener('pointermove', move)
  window.removeEventListener('pointerdown', press); document.removeEventListener('pointerleave', leave)
  window.removeEventListener('blur', leave); document.removeEventListener('visibilitychange', syncMotion)
  media?.removeEventListener('change', syncMotion)
  window.removeEventListener(MOTION_EVENT,syncMotion)
  points = []; ripples = []; sprites.length = 0
})
</script>
<style scoped>
.ambient-particles { position: fixed; inset: 0; width: 100vw; height: 100vh; z-index: -1; pointer-events: none; opacity: .6; }
</style>
