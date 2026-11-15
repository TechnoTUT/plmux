<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { Player } from '../types'
import { api } from '../api'
import { formatDuration, formatPitch } from '../format'
import { useLive } from '../composables/useLive'
import { useWaveform } from '../composables/useWaveform'
import { Playhead } from '../playhead'
import WaveformDetail from './WaveformDetail.vue'
import WaveformPreview from './WaveformPreview.vue'

const props = defineProps<{ player: Player }>()
const { lastBeat } = useLive()

// 拍を受け取ってから 150ms だけインジケーターを強調する
const now = ref(performance.now())
let raf = 0
const tick = () => {
  now.value = performance.now()
  raf = requestAnimationFrame(tick)
}
raf = requestAnimationFrame(tick)
onUnmounted(() => cancelAnimationFrame(raf))

const beatInfo = computed(() => lastBeat[props.player.number])
const flashing = computed(() => props.player.playing && !!beatInfo.value && now.value - beatInfo.value.at < 150)
const currentBeat = computed(() => beatInfo.value?.beatWithinBar || props.player.beatWithinBar)

const track = computed(() => props.player.track)

const waveform = useWaveform(() => props.player.number, () => props.player.track?.waveformKey)
const playhead = new Playhead()
watch(
  () => props.player,
  (p) => playhead.update(p.track ? p.positionMs : -1, p.playing, 1 + p.pitchPercent / 100),
  { immediate: true },
)
const progress = computed(() => {
  const d = track.value?.durationSec
  return d ? Math.min(100, (props.player.positionMs / 1000 / d) * 100) : 0
})
const remaining = computed(() => {
  const d = track.value?.durationSec
  return d ? d - props.player.positionMs / 1000 : 0
})
const artFailed = ref<string | null>(null)
const artUrl = computed(() =>
  track.value && artFailed.value !== track.value.artKey
    ? api.artworkUrl(props.player.number, track.value.artKey)
    : null,
)

const flags = computed(() => [
  { label: 'Play', on: props.player.playing, cls: 'bg-emerald-50 dark:bg-emerald-950/40 text-emerald-700 dark:text-emerald-400 border-emerald-200 dark:border-emerald-800' },
  { label: 'On Air', on: props.player.onAir, cls: 'bg-rose-50 dark:bg-rose-950/40 text-rose-700 dark:text-rose-400 border-rose-200 dark:border-rose-800' },
  { label: 'Master', on: props.player.master, cls: 'bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-400 border-amber-200 dark:border-amber-800' },
  { label: 'Sync', on: props.player.synced, cls: 'bg-purple-50 dark:bg-purple-950/40 text-purple-700 dark:text-purple-400 border-purple-200 dark:border-purple-800' },
])
</script>

<template>
  <article
    class="card space-y-4 min-w-0 transition"
    :class="player.onAir ? 'ring-2 ring-brand/60 border-transparent dark:border-transparent' : ''"
  >
    <!-- Header -->
    <div class="flex items-center gap-3">
      <span
        class="h-9 w-9 rounded-xl grid place-items-center text-lg font-bold tabular-nums"
        :class="player.onAir ? 'bg-brand text-white shadow-md shadow-brand/20' : 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-200'"
      >{{ player.number }}</span>
      <div class="flex-none">
        <h2 class="card-title whitespace-nowrap">Deck {{ player.number }}</h2>
        <p class="text-[11px] text-slate-400 font-mono truncate">{{ player.model }}</p>
      </div>
      <div class="ml-auto flex flex-wrap justify-end gap-1">
        <span
          v-for="f in flags"
          :key="f.label"
          class="px-2 py-0.5 rounded-full text-[10px] font-semibold uppercase tracking-wider border"
          :class="f.on ? f.cls : 'bg-transparent text-slate-300 dark:text-slate-600 border-slate-200 dark:border-slate-800'"
        >{{ f.label }}</span>
      </div>
    </div>

    <!-- Detail waveform -->
    <WaveformDetail v-if="player.trackLoaded" :waveform="waveform" :playhead="playhead" />

    <!-- Track -->
    <div class="flex gap-4 min-w-0">
      <div class="h-20 w-20 flex-none rounded-xl overflow-hidden bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-800">
        <img v-if="artUrl" :src="artUrl" alt="" class="h-full w-full object-cover" @error="artFailed = track?.artKey ?? null" />
        <div v-else class="h-full w-full grid place-items-center text-slate-300 dark:text-slate-600">
          <svg class="w-7 h-7" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" /></svg>
        </div>
      </div>
      <div class="min-w-0 flex flex-col justify-center gap-0.5">
        <template v-if="track">
          <p class="font-bold text-slate-900 dark:text-slate-100 truncate" :title="track.title ?? ''">{{ track.title ?? '(untitled)' }}</p>
          <p class="text-sm text-slate-600 dark:text-slate-300 truncate" :title="track.artist ?? ''">{{ track.artist ?? '(unknown artist)' }}</p>
          <div class="flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-slate-400 font-mono mt-1">
            <span v-if="track.key">{{ track.key }}</span>
            <span v-if="track.bpm">{{ track.bpm.toFixed(1) }} orig</span>
            <span v-if="track.sourceSlot">{{ track.sourcePlayer }}: {{ track.sourceSlot }}</span>
          </div>
        </template>
        <p v-else-if="player.trackLoaded" class="text-sm text-slate-400 animate-pulse">Loading metadata…</p>
        <p v-else class="text-sm text-slate-400">No track loaded</p>
      </div>
    </div>

    <!-- Tempo -->
    <div class="flex items-end gap-3 border-t border-slate-100 dark:border-slate-800 pt-3">
      <div class="font-mono tabular-nums leading-none">
        <span class="text-3xl font-bold text-slate-900 dark:text-slate-100">{{ player.bpm > 0 ? player.bpm.toFixed(1) : '--.-' }}</span>
        <span class="ml-1 text-[10px] font-sans font-semibold uppercase text-slate-400">BPM</span>
      </div>
      <span class="font-mono tabular-nums text-xs text-slate-500 dark:text-slate-400 pb-0.5">{{ formatPitch(player.pitchPercent) }}</span>
      <div class="ml-auto flex gap-1.5 pb-1">
        <span
          v-for="b in 4"
          :key="b"
          class="h-3.5 w-3.5 rounded transition-colors duration-75"
          :class="currentBeat === b && player.playing
            ? (flashing ? (player.master ? 'bg-amber-400' : 'bg-brand') : 'bg-slate-400 dark:bg-slate-500')
            : 'bg-slate-200 dark:bg-slate-800'"
        />
      </div>
    </div>

    <!-- Progress -->
    <div class="space-y-1">
      <WaveformPreview
        v-if="waveform?.preview"
        :waveform="waveform"
        :playhead="playhead"
        :duration-ms="(track?.durationSec ?? 0) * 1000"
      />
      <div v-else class="h-2 bg-slate-200 dark:bg-slate-800 rounded-full overflow-hidden">
        <div
          class="h-full rounded-full transition-[width] duration-200 ease-linear"
          :class="player.onAir ? 'bg-brand' : 'bg-slate-400 dark:bg-slate-500'"
          :style="{ width: progress + '%' }"
        />
      </div>
      <div class="flex justify-between text-[11px] font-mono tabular-nums text-slate-500 dark:text-slate-400">
        <span>{{ formatDuration(player.positionMs / 1000) }}</span>
        <span>-{{ formatDuration(remaining) }}</span>
      </div>
    </div>
  </article>
</template>
