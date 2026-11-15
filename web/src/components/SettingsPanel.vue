<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { LinkMode, OverlayPosition, OverlaySettings, Settings, WaveformStyle } from '../types'
import { api, type SettingsPatch } from '../api'
import { useLive } from '../composables/useLive'

const waveformStyle = ref<WaveformStyle | null>(null)
const linkStartupMode = ref<LinkMode | null>(null)
const overlay = ref<OverlaySettings | null>(null)
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

const positions: { value: OverlayPosition; label: string }[] = [
  { value: 'TOP_LEFT', label: 'Top left' },
  { value: 'TOP_RIGHT', label: 'Top right' },
  { value: 'BOTTOM_LEFT', label: 'Bottom left' },
  { value: 'BOTTOM_RIGHT', label: 'Bottom right' },
]

function apply(s: Settings) {
  waveformStyle.value = s.waveformStyle
  linkStartupMode.value = s.linkStartupMode
  overlay.value = s.overlay
}

// 他のブラウザで変更されたときも表示を合わせる
const stopListening = useLive().onSettings(apply)
onBeforeUnmount(stopListening)

onMounted(async () => {
  try {
    apply(await api.getSettings())
  } catch (e) {
    error.value = String(e)
  }
})

async function update(patch: SettingsPatch) {
  busy.value = true
  error.value = null
  try {
    apply(await api.updateSettings(patch))
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}

const overlayUrl = `${location.origin}/overlay`
const copied = ref(false)
async function copyOverlayUrl() {
  try {
    await navigator.clipboard.writeText(overlayUrl)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    // http で開いているとクリップボード API が使えないことがある。URL は表示しているので手でコピーしてもらう
  }
}

// プレビューは 1920x1080 で描画して枠の幅に縮小する (OBS の出力と同じ見え方にするため)
const previewBox = ref<HTMLElement | null>(null)
const previewScale = ref(0.25)
const observer = new ResizeObserver(([e]) => (previewScale.value = e.contentRect.width / 1920))
onMounted(() => previewBox.value && observer.observe(previewBox.value))
onBeforeUnmount(() => observer.disconnect())
const previewStyle = computed(() => ({ transform: `scale(${previewScale.value})` }))
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <p v-if="error" class="lg:col-span-3 text-sm text-rose-600">{{ error }}</p>
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

    <section class="card lg:col-span-2 space-y-4">
      <div>
        <h2 class="card-title">OBS Overlay</h2>
        <p class="text-xs text-slate-400 mt-1">
          Shows the track on the tempo master deck. Add a Browser source in OBS (1920×1080) with this URL.
          Changes here apply to open overlays immediately.
        </p>
      </div>
      <div class="flex gap-2">
        <input class="input font-mono text-xs" :value="overlayUrl" readonly @focus="($event.target as HTMLInputElement).select()" />
        <button class="btn whitespace-nowrap" @click="copyOverlayUrl">{{ copied ? 'Copied' : 'Copy' }}</button>
      </div>

      <div
        ref="previewBox"
        class="relative w-full aspect-video rounded-xl overflow-hidden border border-slate-200 dark:border-slate-800 preview-bg"
      >
        <iframe
          src="/overlay.html"
          title="Overlay preview"
          class="absolute top-0 left-0 w-[1920px] h-[1080px] origin-top-left pointer-events-none [color-scheme:light]"
          :style="previewStyle"
        />
      </div>

      <div v-if="overlay" class="grid sm:grid-cols-2 gap-4 text-sm">
        <div class="space-y-2">
          <p class="font-medium text-slate-600 dark:text-slate-300">Theme</p>
          <div class="flex gap-2">
            <button
              v-for="t in (['DARK', 'LIGHT'] as const)"
              :key="t"
              :disabled="busy"
              :class="overlay.theme === t ? 'btn-primary' : 'btn'"
              @click="update({ overlay: { theme: t } })"
            >{{ t === 'DARK' ? 'Dark' : 'Light' }}</button>
          </div>
        </div>
        <div class="space-y-2">
          <p class="font-medium text-slate-600 dark:text-slate-300">Position</p>
          <div class="grid grid-cols-2 gap-2">
            <button
              v-for="p in positions"
              :key="p.value"
              :disabled="busy"
              :class="overlay.position === p.value ? 'btn-primary' : 'btn'"
              @click="update({ overlay: { position: p.value } })"
            >{{ p.label }}</button>
          </div>
        </div>
        <label class="flex items-center gap-2 text-slate-700 dark:text-slate-200">
          <input
            type="checkbox"
            class="checkbox"
            :checked="overlay.showArtwork"
            :disabled="busy"
            @change="update({ overlay: { showArtwork: ($event.target as HTMLInputElement).checked } })"
          />
          Show artwork
        </label>
        <label class="flex items-center gap-2 text-slate-700 dark:text-slate-200">
          <input
            type="checkbox"
            class="checkbox"
            :checked="overlay.hideWhenIdle"
            :disabled="busy"
            @change="update({ overlay: { hideWhenIdle: ($event.target as HTMLInputElement).checked } })"
          />
          Hide when the master deck is stopped
        </label>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* 透過部分が分かるように市松模様にする */
.preview-bg {
  background-color: #94a3b8;
  background-image:
    linear-gradient(45deg, #64748b 25%, transparent 25%),
    linear-gradient(-45deg, #64748b 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #64748b 75%),
    linear-gradient(-45deg, transparent 75%, #64748b 75%);
  background-size: 24px 24px;
  background-position: 0 0, 0 12px, 12px -12px, -12px 0;
}
</style>
