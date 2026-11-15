<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { LinkMode, WaveformStyle } from '../types'
import { api } from '../api'

const waveformStyle = ref<WaveformStyle | null>(null)
const linkStartupMode = ref<LinkMode | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)

const styles: { value: WaveformStyle; label: string; help: string; swatch: string[] }[] = [
  {
    value: 'THREE_BAND',
    label: 'Three-band',
    help: 'Low / mid / high frequencies in blue, amber and white, as introduced with the CDJ-3000.',
    swatch: ['rgb(32,83,217)', 'rgb(169,107,39)', 'rgb(242,170,60)', 'rgb(255,255,255)'],
  },
  {
    value: 'RGB',
    label: 'RGB',
    help: 'Full-color waveform from the nexus2 generation. Color shows the frequency content.',
    swatch: ['rgb(230,40,40)', 'rgb(230,160,30)', 'rgb(60,200,90)', 'rgb(60,120,255)'],
  },
  {
    value: 'BLUE',
    label: 'Blue',
    help: 'Classic blue-and-white waveform used by older players.',
    swatch: ['rgb(0,104,144)', 'rgb(0,168,232)', 'rgb(136,192,232)', 'rgb(200,224,232)'],
  },
]

const linkModes: { value: LinkMode; label: string; help: string }[] = [
  { value: 'OFF', label: 'Off', help: 'Link stays disabled until it is turned on in the Link tab.' },
  { value: 'MONITOR', label: 'Monitor', help: 'Connect to Carabiner and show the Link session tempo and peers.' },
  { value: 'FOLLOW', label: 'Follow', help: 'Make the Link session follow the DJ Link tempo master right away.' },
]

onMounted(async () => {
  try {
    const s = await api.getSettings()
    waveformStyle.value = s.waveformStyle
    linkStartupMode.value = s.linkStartupMode
  } catch (e) {
    error.value = String(e)
  }
})

async function update(patch: { waveformStyle?: WaveformStyle; linkStartupMode?: LinkMode }) {
  busy.value = true
  error.value = null
  try {
    const s = await api.updateSettings(patch)
    waveformStyle.value = s.waveformStyle
    linkStartupMode.value = s.linkStartupMode
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <section class="card lg:col-span-2 space-y-4">
      <div>
        <h2 class="card-title">Waveform Style</h2>
        <p class="text-xs text-slate-400 mt-1">
          Applies to every deck and is saved on the server. If a player cannot provide the selected style, another one is used.
        </p>
      </div>
      <div class="grid gap-3">
        <button
          v-for="s in styles"
          :key="s.value"
          :disabled="busy"
          class="text-left p-4 rounded-2xl border-2 transition disabled:opacity-60"
          :class="waveformStyle === s.value
            ? 'border-brand bg-rose-50/60 dark:bg-brand-dim/20'
            : 'border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700'"
          @click="update({ waveformStyle: s.value })"
        >
          <div class="flex items-center gap-3">
            <span
              class="h-4 w-4 rounded-full border-2 grid place-items-center flex-none"
              :class="waveformStyle === s.value ? 'border-brand' : 'border-slate-300 dark:border-slate-600'"
            >
              <span v-if="waveformStyle === s.value" class="h-2 w-2 rounded-full bg-brand" />
            </span>
            <span class="font-semibold text-slate-900 dark:text-slate-100">{{ s.label }}</span>
            <span class="ml-auto flex h-4 rounded overflow-hidden bg-slate-950 p-0.5 gap-0.5">
              <span v-for="c in s.swatch" :key="c" class="w-4 rounded-sm" :style="{ background: c }" />
            </span>
          </div>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 ml-7">{{ s.help }}</p>
        </button>
      </div>
      <p v-if="error" class="text-sm text-rose-600">{{ error }}</p>
    </section>

    <section class="card lg:col-span-2 space-y-4">
      <div>
        <h2 class="card-title">Ableton Link on Startup</h2>
        <p class="text-xs text-slate-400 mt-1">
          Link mode applied when the server starts. Saved on the server; takes effect on the next restart.
        </p>
      </div>
      <div class="grid gap-3">
        <button
          v-for="m in linkModes"
          :key="m.value"
          :disabled="busy"
          class="text-left p-4 rounded-2xl border-2 transition disabled:opacity-60"
          :class="linkStartupMode === m.value
            ? 'border-brand bg-rose-50/60 dark:bg-brand-dim/20'
            : 'border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700'"
          @click="update({ linkStartupMode: m.value })"
        >
          <div class="flex items-center gap-3">
            <span
              class="h-4 w-4 rounded-full border-2 grid place-items-center flex-none"
              :class="linkStartupMode === m.value ? 'border-brand' : 'border-slate-300 dark:border-slate-600'"
            >
              <span v-if="linkStartupMode === m.value" class="h-2 w-2 rounded-full bg-brand" />
            </span>
            <span class="font-semibold text-slate-900 dark:text-slate-100">{{ m.label }}</span>
          </div>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 ml-7">{{ m.help }}</p>
        </button>
      </div>
    </section>
  </div>
</template>
