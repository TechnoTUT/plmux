<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { OverlaySettings, Track } from '../types'
import { api } from '../api'
import { useLive } from '../composables/useLive'
import MarqueeText from './MarqueeText.vue'
import RevealText from './RevealText.vue'

/**
 * OBS のブラウザソース用 Now Playing オーバーレイ。テンポマスターのデッキの曲を表示する。
 * 見た目は Settings タブで変更し、WebSocket 経由で即時反映される。
 */
const { state, onSettings } = useLive()

const settings = ref<OverlaySettings>({ theme: 'DARK', position: 'BOTTOM_LEFT', showArtwork: true, hideWhenIdle: false })
onMounted(async () => {
  try {
    settings.value = (await api.getSettings()).overlay
  } catch {
    // サーバーに繋がるまでは既定値で表示し、WebSocket の settings で更新する
  }
})
onSettings((s) => (settings.value = s.overlay))

const master = computed(() => {
  const n = state.value?.masterPlayer
  return n == null ? null : (state.value?.players.find((p) => p.number === n) ?? null)
})

/** マスターがいなくなっても (全デッキ停止など) 最後の曲を出し続ける */
const shown = ref<{ player: number; track: Track } | null>(null)
watch(
  master,
  (p) => {
    if (p?.track && (shown.value?.player !== p.number || shown.value.track.artKey !== p.track.artKey
      || shown.value.track.rekordboxId !== p.track.rekordboxId)) {
      shown.value = { player: p.number, track: p.track }
    }
  },
  { immediate: true },
)

const idle = computed(() => !master.value?.playing)
const visible = computed(() => !!shown.value && !(settings.value.hideWhenIdle && idle.value))

const artFailed = ref<string | null>(null)
const artUrl = computed(() => {
  const s = shown.value
  if (!s || !settings.value.showArtwork || artFailed.value === s.track.artKey) return null
  return api.artworkUrl(s.player, s.track.artKey)
})

/** 曲が変わったらカードを入れ替えるアニメーションのキー */
const trackKey = computed(() => (shown.value ? `${shown.value.player}:${shown.value.track.rekordboxId}:${shown.value.track.artKey}` : ''))

const positionClass = computed(() => ({
  TOP_LEFT: 'top-0 left-0 items-start',
  TOP_RIGHT: 'top-0 right-0 items-end',
  BOTTOM_LEFT: 'bottom-0 left-0 items-start',
  BOTTOM_RIGHT: 'bottom-0 right-0 items-end',
}[settings.value.position]))
const dark = computed(() => settings.value.theme === 'DARK')
</script>

<template>
  <div class="fixed p-12 flex flex-col max-w-full font-sans" :class="positionClass">
    <Transition name="card" mode="out-in">
      <div
        v-if="visible && shown"
        :key="trackKey"
        class="flex items-center gap-6 rounded-3xl p-4 pr-10 max-w-[56rem] min-w-[28rem] shadow-2xl border backdrop-blur"
        :class="dark
          ? 'bg-slate-900/85 border-slate-700/60 text-white'
          : 'bg-white/90 border-slate-200 text-slate-900'"
      >
        <div
          v-if="settings.showArtwork"
          class="h-32 w-32 flex-none rounded-2xl overflow-hidden grid place-items-center"
          :class="dark ? 'bg-slate-800' : 'bg-slate-100'"
        >
          <img v-if="artUrl" :src="artUrl" alt="" class="h-full w-full object-cover" @error="artFailed = shown.track.artKey" />
          <svg v-else viewBox="0 0 24 24" class="h-12 w-12" :class="dark ? 'text-slate-600' : 'text-slate-300'" fill="currentColor">
            <path d="M12 3v10.55A4 4 0 1 0 14 17V7h4V3h-6z" />
          </svg>
        </div>
        <div class="min-w-0 flex-1 border-l-[6px] border-brand pl-6 py-1">
          <!-- カードがスライドインしてから、ラベル → 曲名 → アーティストの順に 1 文字ずつ現れる -->
          <p class="text-base font-bold uppercase tracking-[0.25em] text-brand-light">
            <RevealText text="Now Playing" :delay="0.2" />
          </p>
          <MarqueeText
            class="text-4xl font-bold leading-tight mt-1"
            :text="shown.track.title || 'Unknown Title'"
            :reveal-delay="0.4"
          />
          <MarqueeText
            class="text-2xl mt-1"
            :class="dark ? 'text-slate-300' : 'text-slate-600'"
            :text="shown.track.artist || 'Unknown Artist'"
            :reveal-delay="0.6"
          />
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.card-enter-active,
.card-leave-active {
  transition: opacity 0.4s ease, transform 0.4s ease;
}
.card-enter-from,
.card-leave-to {
  opacity: 0;
  transform: translateY(12px);
}
</style>
