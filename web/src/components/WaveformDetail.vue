<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { Waveform } from '../types'
import { FRAMES_PER_MS, type Playhead } from '../playhead'
import { useCanvas } from '../composables/useCanvas'

/**
 * CDJ のメイン画面のような、再生位置を中心にスクロールする詳細波形。
 * 描画は beat-link の WaveformDetailComponent に合わせている。
 */
const props = defineProps<{ waveform: Waveform | null; playhead: Playhead }>()

const ZOOMS_MS = [4000, 8000, 16000]  // 画面幅に表示する時間
const zoom = ref(1)

// beat-link WaveformFinder.ThreeBandLayer と同じ色
const LOW = 'rgb(32,83,217)'
const LOW_AND_MID = 'rgb(169,107,39)'
const MID = 'rgb(242,170,60)'
const HIGH = 'rgb(255,255,255)'
const BEAT_TICK = 5  // px (CSS)

const canvas = ref<HTMLCanvasElement | null>(null)
const { ctx, size } = useCanvas(canvas)

let raf = 0
function frame() {
  draw()
  raf = requestAnimationFrame(frame)
}
onMounted(() => (raf = requestAnimationFrame(frame)))
onUnmounted(() => cancelAnimationFrame(raf))

/** 二分探索: times[i] >= t となる最初の i */
function lowerBound(times: number[], t: number): number {
  let lo = 0
  let hi = times.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (times[mid] < t) lo = mid + 1
    else hi = mid
  }
  return lo
}

function draw() {
  const g = ctx.value
  if (!g) return
  const { width, height, dpr } = size.value
  g.clearRect(0, 0, width, height)
  const w = props.waveform
  const pos = props.playhead.positionAt()
  const cx = Math.round(width / 2)
  const tick = BEAT_TICK * dpr
  const axis = height / 2
  const maxH = axis - tick - 2 * dpr
  const msPerPx = ZOOMS_MS[zoom.value] / width
  const toX = (ms: number) => cx + (ms - (pos ?? 0)) / msPerPx

  if (w && pos !== null) {
    const d = w.detail
    if (d) {
      const b = d.bytes
      const scale = maxH / 31
      for (let x = 0; x < width; x++) {
        const t0 = pos + (x - cx) * msPerPx
        let f0 = Math.floor(t0 * FRAMES_PER_MS)
        let f1 = Math.max(f0 + 1, Math.floor((t0 + msPerPx) * FRAMES_PER_MS))
        if (f1 <= 0 || f0 >= d.frames) continue
        f0 = Math.max(0, f0)
        f1 = Math.min(d.frames, f1)
        if (w.style === 'THREE_BAND') {
          let low = 0, mid = 0, high = 0
          for (let f = f0; f < f1; f++) {
            const i = f * 3
            low = Math.max(low, b[i]); mid = Math.max(mid, b[i + 1]); high = Math.max(high, b[i + 2])
          }
          const lh = Math.min(maxH, low * scale), mh = Math.min(maxH, mid * scale), hh = Math.min(maxH, high * scale)
          if (lh > mh) {
            g.fillStyle = LOW; g.fillRect(x, axis - lh, 1, lh * 2)
            g.fillStyle = LOW_AND_MID; g.fillRect(x, axis - mh, 1, mh * 2)
          } else {
            if (lh !== mh) { g.fillStyle = MID; g.fillRect(x, axis - mh, 1, mh * 2) }
            g.fillStyle = LOW_AND_MID; g.fillRect(x, axis - lh, 1, lh * 2)
          }
          g.fillStyle = HIGH; g.fillRect(x, axis - hh, 1, hh * 2)
        } else {
          // 区間内で一番高いフレームの色と高さを使う
          let best = f0
          for (let f = f0 + 1; f < f1; f++) if (b[f * 4] > b[best * 4]) best = f
          const i = best * 4
          const h = Math.min(maxH, b[i] * scale)
          g.fillStyle = `rgb(${b[i + 1]},${b[i + 2]},${b[i + 3]})`
          g.fillRect(x, axis - h, 1, h * 2)
        }
      }
    }

    // ループ範囲
    const tStart = pos - cx * msPerPx
    const tEnd = pos + (width - cx) * msPerPx
    for (const c of w.cues) {
      if (c.endMs !== null && c.endMs > tStart && c.timeMs < tEnd) {
        g.fillStyle = 'rgba(255,140,0,0.22)'
        g.fillRect(toX(c.timeMs), 0, (c.endMs - c.timeMs) / msPerPx, height)
      }
    }

    // ビートグリッド (小節の頭は赤く太く)
    const times = w.beatTimesMs
    for (let i = lowerBound(times, tStart); i < times.length && times[i] <= tEnd; i++) {
      const x = Math.round(toX(times[i]))
      const downbeat = w.beatsWithinBar[i] === 1
      if (!downbeat && zoom.value === ZOOMS_MS.length - 1) continue  // 最も引いたときは小節線だけ (CDJ と同じ)
      g.fillStyle = downbeat ? '#ff2a36' : 'rgba(255,255,255,0.75)'
      const lw = (downbeat ? 2 : 1) * dpr
      g.fillRect(x - lw / 2, 0, lw, tick)
      g.fillRect(x - lw / 2, height - tick, lw, tick)
    }

    // キュー (ホットキューは色付きの旗 + 文字、メモリーポイントは三角)
    g.font = `bold ${9 * dpr}px ui-sans-serif, system-ui, sans-serif`
    g.textBaseline = 'top'
    for (const c of w.cues) {
      if (c.timeMs < tStart - 20 * msPerPx || c.timeMs > tEnd) continue
      const x = Math.round(toX(c.timeMs))
      const color = c.color ?? (c.hotCue ? '#30d26e' : '#ff2a36')
      g.fillStyle = color
      if (c.hotCue > 0) {
        const label = String.fromCharCode(64 + c.hotCue)
        const bw = 11 * dpr
        g.fillRect(x, 0, bw, 11 * dpr)
        g.fillRect(x, 0, 1.5 * dpr, height)
        g.fillStyle = '#000'
        g.fillText(label, x + 2.5 * dpr, 1 * dpr)
      } else {
        const s = 5 * dpr
        g.beginPath()
        g.moveTo(x - s, 0); g.lineTo(x + s, 0); g.lineTo(x, s * 1.4)
        g.closePath()
        g.fill()
      }
    }
  }

  // 再生位置
  g.fillStyle = 'rgba(0,0,0,0.5)'
  g.fillRect(cx - 2 * dpr, 0, 4 * dpr, height)
  g.fillStyle = '#ffffff'
  g.fillRect(cx - dpr, 0, 2 * dpr, height)
}
</script>

<template>
  <div class="relative rounded-xl overflow-hidden bg-slate-950 border border-slate-200 dark:border-slate-800">
    <canvas ref="canvas" class="block w-full h-20" />
    <div v-if="!waveform?.detail" class="absolute inset-0 grid place-items-center text-[11px] text-slate-500 pointer-events-none">
      {{ waveform ? 'Loading waveform…' : 'No waveform' }}
    </div>
    <div class="absolute bottom-1 right-1 flex gap-0.5">
      <button
        v-for="(ms, i) in ZOOMS_MS"
        :key="ms"
        class="px-1.5 py-px rounded text-[9px] font-mono font-semibold transition"
        :class="zoom === i ? 'bg-white/80 text-slate-900' : 'bg-white/10 text-white/60 hover:bg-white/25'"
        :title="`Show ${ms / 1000} seconds`"
        @click="zoom = i"
      >{{ ms / 1000 }}s</button>
    </div>
  </div>
</template>
