<template>
  <span ref="host" class="celestial-title" tabindex="0" :aria-label="text"
        @pointerdown="startDrag" @pointermove="drag" @pointerup="release" @pointercancel="release"
        @mouseenter="accent" @focus="accent" @keydown.enter.prevent="accent" @keydown.space.prevent="accent">
    <span ref="letters" class="divine-title celestial-title-text">{{ text }}</span>
    <canvas ref="canvas" aria-hidden="true" />
  </span>
</template>
<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import {currentMotion,MOTION_EVENT} from '../utils/motionPreferences'
const props = defineProps({ text: { type: String, required: true } })
const host = ref(null), letters = ref(null), canvas = ref(null)
let ctx, source, pieces = [], observer, media, frame = 0, alive = true
let width = 0, height = 0, progress = 0, held = false, previousX = 0, lastFrame = 0
let animation = null
const margin = 36
function paint(value) {
  progress = value
  if (!ctx || !width || !height) return
  ctx.clearRect(0, 0, width + margin * 2, height + margin * 2)
  letters.value.style.opacity = value ? String(Math.max(.14, 1 - value * 2)) : '1'
  if (!value) return
  for (const piece of pieces) {
    const p = Math.min(1, value * (1 + piece.delay)), q = 1 - p
    const dx = 3 * q * q * p * piece.dx + 3 * q * p * p * piece.dx * 1.5 + p * p * p * piece.dx * 1.8
    const dy = 3 * q * q * p * piece.dy + 3 * q * p * p * piece.dy * .65
    ctx.save(); ctx.globalAlpha = Math.min(1, value * 6) * (1 - p * .35)
    ctx.translate(margin + piece.cx + dx, margin + piece.cy + dy)
    ctx.rotate(p * piece.angle); ctx.scale(1 - p * .25, 1 - p * .25)
    ctx.beginPath()
    piece.vertices.forEach(([x, y], i) => i ? ctx.lineTo(x - piece.cx, y - piece.cy) : ctx.moveTo(x - piece.cx, y - piece.cy))
    ctx.closePath(); ctx.clip(); ctx.drawImage(source, -piece.cx, -piece.cy, width, height); ctx.restore()
  }
}
function stop() { cancelAnimationFrame(frame); frame = 0; animation = null; lastFrame = 0 }
function tick(time) {
  if (!alive || document.hidden || media.matches || currentMotion()!=='FULL' || !animation) { stop(); paint(0); return }
  if (time - lastFrame >= 32) {
    const t = Math.min(1, (time - animation.start) / animation.duration)
    const eased = .5 - Math.cos(t * Math.PI) * .5
    paint(animation.from + (animation.to - animation.from) * eased); lastFrame = time
    if (t === 1) {
      const next = animation.returnHome; stop()
      if (next) animateTo(0, 650)
      return
    }
  }
  frame = requestAnimationFrame(tick)
}
function animateTo(to, duration = 700, returnHome = false) {
  stop()
  if (!ctx || !pieces.length || document.hidden || media?.matches || currentMotion()!=='FULL') { paint(0); return }
  animation = { from: progress, to, duration, start: performance.now(), returnHome }
  frame = requestAnimationFrame(tick)
}
function accent() { if (!held && !animation && !media?.matches) animateTo(.27, 550, true) }
function startDrag(event) {
  if (event.pointerType !== 'mouse' || event.button !== 0 || media?.matches || currentMotion()!=='FULL' || !pieces.length) return
  held = true; previousX = event.clientX; stop(); event.preventDefault()
  host.value.setPointerCapture(event.pointerId)
}
function drag(event) {
  if (!held) return
  paint(Math.max(0, Math.min(.8, progress + (event.clientX - previousX) / Math.max(100, width))))
  previousX = event.clientX
}
function release() { if (held) { held = false; animateTo(0) } }
function settle() { held = false; stop(); paint(0) }
function rebuild() {
  settle()
  const box = letters.value.getBoundingClientRect()
  width = Math.ceil(box.width); height = Math.ceil(box.height)
  if (!width || !height) return
  const dpr = Math.min(devicePixelRatio || 1, 1.5)
  canvas.value.width = Math.ceil((width + margin * 2) * dpr)
  canvas.value.height = Math.ceil((height + margin * 2) * dpr)
  canvas.value.style.width = `${width + margin * 2}px`; canvas.value.style.height = `${height + margin * 2}px`
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  source = document.createElement('canvas'); source.width = Math.ceil(width * dpr); source.height = Math.ceil(height * dpr)
  const pen = source.getContext('2d'), style = getComputedStyle(letters.value)
  // Long scene names can wrap naturally; keep them readable instead of rasterizing one cropped line.
  if (height > parseFloat(style.fontSize) * 1.8) { pieces = []; paint(0); return }
  pen.scale(dpr, dpr); pen.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
  if ('letterSpacing' in pen) pen.letterSpacing = style.letterSpacing
  const gradient = pen.createLinearGradient(0, 0, width, height)
  gradient.addColorStop(0, '#b59a69'); gradient.addColorStop(.5, '#e5ddc9'); gradient.addColorStop(1, '#b9cce5')
  pen.fillStyle = gradient; pen.textBaseline = 'middle'; pen.fillText(props.text, 0, height / 2)
  const columns = Math.min(24, Math.max(8, props.text.length * 3)), rows = 4
  pieces = []
  for (let y = 0; y < rows; y++) for (let x = 0; x < columns; x++) {
    const x0 = x * width / columns, x1 = (x + 1) * width / columns, y0 = y * height / rows, y1 = (y + 1) * height / rows
    for (const vertices of [[[x0, y0], [x1, y0], [x1, y1]], [[x0, y0], [x1, y1], [x0, y1]]]) {
      const cx = vertices.reduce((sum, v) => sum + v[0], 0) / 3, cy = vertices.reduce((sum, v) => sum + v[1], 0) / 3
      pieces.push({ vertices, cx, cy, dx: (cx / width - .5) * 38, dy: (cy / height - .5) * 64,
        angle: Math.sin(x * 7 + y * 3) * .75, delay: (x + y) % 5 * .09 })
    }
  }
  paint(0)
}
onMounted(async () => {
  ctx = canvas.value.getContext('2d'); if (!ctx) return
  media = matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', settle); document.addEventListener('visibilitychange', settle)
  window.addEventListener('blur', settle)
  window.addEventListener(MOTION_EVENT,settle)
  observer = new ResizeObserver(rebuild); observer.observe(letters.value)
  await document.fonts.ready
  if (!alive) return
  rebuild()
  if (!media.matches && currentMotion()==='FULL' && !document.hidden) { paint(.65); animateTo(0, 1100) }
})
watch(() => props.text, async () => { await nextTick(); if (alive && ctx) rebuild() })
onBeforeUnmount(() => {
  alive = false; stop(); observer?.disconnect(); media?.removeEventListener('change', settle)
  document.removeEventListener('visibilitychange', settle); window.removeEventListener('blur', settle)
  window.removeEventListener(MOTION_EVENT,settle)
  pieces = []; source = null
})
</script>
<style scoped>
.celestial-title { position: relative; display: inline-block; max-width: 100%; pointer-events: auto; touch-action: pan-y; }
.celestial-title-text { display: inline-block; }
.celestial-title canvas { position: absolute; left: -36px; top: -36px; pointer-events: none; }
.celestial-title:focus-visible { outline: 1px solid #c1a97688; outline-offset: 6px; }
@media (prefers-reduced-motion: reduce), (forced-colors: active) { .celestial-title canvas { display: none; } }
</style>
