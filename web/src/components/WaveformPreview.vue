<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import type { Waveform } from '../types'
import type { Playhead } from '../playhead'
import { useCanvas } from '../composables/useCanvas'

/**
 * 曲全体の波形 (CDJ 下部のプレビュー)。再生済みの部分を暗くし、再生位置とキューを重ねる。
 * 描画は beat-link の WaveformPreviewComponent に合わせている。
 */
const props = defineProps<{ waveform: Waveform | null; playhead: Playhead; durationMs: number }>()

const LOW = 'rgb(32,83,217)'
const MID = 'rgb(242,170,60)'
const HIGH = 'rgb(255,255,255)'
const CUE_AREA = 5  // px (CSS)。下端のキュー表示用

const canvas = ref<HTMLCanvasElement | null>(null)
const { ctx, size } = useCanvas(canvas)

// 波形本体は変化したときだけ offscreen canvas に描いておく
let image: HTMLCanvasElement | null = null
function renderImage() {
  const w = props.waveform
  const p = w?.preview
  const { width, height, dpr } = size.value
  if (!w || !p) {
    image = null
    return
  }
  const off = document.createElement('canvas')
  off.width = width
  off.height = height
  const g = off.getContext('2d')!
  const bottom = height - CUE_AREA * dpr
  const scale = bottom / Math.max(1, p.maxHeight)
  const b = p.bytes
  const bar = (x: number, h: number, color: string) => {
    const px = Math.min(bottom, h * scale)
    g.fillStyle = color
    g.fillRect(x, bottom - px, 1, px)
  }
  for (let x = 0; x < width; x++) {
    // この列に含まれるセグメントのうち最も高いものを描く
    const s0 = Math.floor((x * p.segments) / width)
    const s1 = Math.max(s0 + 1, Math.floor(((x + 1) * p.segments) / width))
    let best = s0
    for (let s = s0 + 1; s < s1 && s < p.segments; s++) {
      if (b[s * p.stride + (w.style === 'THREE_BAND' ? 2 : 0)] > b[best * p.stride + (w.style === 'THREE_BAND' ? 2 : 0)]) best = s
    }
    const i = best * p.stride
    if (w.style === 'THREE_BAND') {
      bar(x, b[i + 2], HIGH)
      bar(x, b[i + 1], MID)
      bar(x, b[i], LOW)
    } else {
      bar(x, b[i], `rgb(${b[i + 1]},${b[i + 2]},${b[i + 3]})`)
      if (w.style === 'RGB') bar(x, b[i + 4], `rgb(${b[i + 5]},${b[i + 6]},${b[i + 7]})`)
    }
  }
  image = off
}
watch([() => props.waveform, size], renderImage)

let raf = 0
function frame() {
  draw()
  raf = requestAnimationFrame(frame)
}
onMounted(() => (raf = requestAnimationFrame(frame)))
onUnmounted(() => cancelAnimationFrame(raf))

function draw() {
  const g = ctx.value
  if (!g) return
  const { width, height, dpr } = size.value
  g.clearRect(0, 0, width, height)
  if (image) g.drawImage(image, 0, 0)
  const w = props.waveform
  const duration = props.durationMs || w?.durationMs || 0
  if (!duration) return
  const toX = (ms: number) => (ms / duration) * width

  // キュー (下端)
  if (w) {
    for (const c of w.cues) {
      const x = Math.round(toX(c.timeMs))
      g.fillStyle = c.color ?? (c.hotCue ? '#30d26e' : '#ff2a36')
      if (c.endMs !== null) {
        g.globalAlpha = 0.35
        g.fillRect(x, 0, Math.max(dpr, toX(c.endMs) - x), height - CUE_AREA * dpr)
        g.globalAlpha = 1
      }
      const s = 3.5 * dpr
      g.beginPath()
      g.moveTo(x, height - CUE_AREA * dpr)
      g.lineTo(x + s, height)
      g.lineTo(x - s, height)
      g.closePath()
      g.fill()
    }
  }

  // 再生済み部分を暗く + 再生位置
  const pos = props.playhead.positionAt()
  if (pos !== null) {
    const x = Math.min(width, Math.max(0, toX(pos)))
    g.fillStyle = 'rgba(2,6,23,0.55)'
    g.fillRect(0, 0, x, height - CUE_AREA * dpr)
    g.fillStyle = '#ff2a36'
    g.fillRect(x - dpr, 0, 2 * dpr, height)
  }
}
</script>

<template>
  <div class="relative rounded-lg overflow-hidden bg-slate-950 border border-slate-200 dark:border-slate-800">
    <canvas ref="canvas" class="block w-full h-10" />
  </div>
</template>
