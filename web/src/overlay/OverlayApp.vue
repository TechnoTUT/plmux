<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { OverlaySettings, OverlayTag, Track } from '../types'
import { api } from '../api'
import { useLive } from '../composables/useLive'
import MarqueeText from './MarqueeText.vue'
import RevealText from './RevealText.vue'

/**
 * OBS のブラウザソース用 Now Playing オーバーレイ。テンポマスターのデッキの曲を表示する。
 * 見た目は prolink-tools (https://prolink.tools/) の Track List テーマに寄せている:
 * カードは使わず、各行の後ろにだけ半透明の帯を敷き、アートワークを横に並べる。
 * 見た目は Settings タブで変更し、WebSocket 経由で即時反映される。
 */
const { state, onSettings } = useLive()

const settings = ref<OverlaySettings>({
  theme: 'DARK',
  position: 'BOTTOM_LEFT',
  showArtwork: true,
  hideWhenIdle: false,
  backgroundOpacity: 25,
  tags: ['ALBUM', 'GENRE'],
})
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

/** 曲が変わったら表示を入れ替えるアニメーションのキー */
const trackKey = computed(() => (shown.value ? `${shown.value.player}:${shown.value.track.rekordboxId}:${shown.value.track.artKey}` : ''))

const positionClass = computed(() => ({
  TOP_LEFT: 'top-0 left-0',
  TOP_RIGHT: 'top-0 right-0',
  BOTTOM_LEFT: 'bottom-0 left-0',
  BOTTOM_RIGHT: 'bottom-0 right-0',
}[settings.value.position]))
const alignRight = computed(() => settings.value.position.endsWith('RIGHT'))
const dark = computed(() => settings.value.theme === 'DARK')

/** 各行の後ろの帯。不透明度が低いときは文字に影を付けて読みやすくする */
const lineStyle = computed(() => {
  const a = settings.value.backgroundOpacity / 100
  const shadow = a < 0.4
    ? (dark.value ? '0 1px 3px rgba(0,0,0,0.8), 0 0 8px rgba(0,0,0,0.5)' : '0 0 3px rgba(255,255,255,0.9), 0 0 8px rgba(255,255,255,0.6)')
    : 'none'
  return {
    background: dark.value ? `rgba(0,0,0,${a})` : `rgba(255,255,255,${a})`,
    textShadow: shadow,
  }
})

function formatBpm(bpm: number): string {
  return `${Number.isInteger(bpm) ? bpm : bpm.toFixed(1)} BPM`
}
const tagValues = computed(() => {
  const t = shown.value?.track
  if (!t) return []
  const value: Record<OverlayTag, string | null> = {
    ALBUM: t.album,
    GENRE: t.genre,
    KEY: t.key,
    BPM: t.bpm > 0 ? formatBpm(t.bpm) : null,
  }
  return settings.value.tags.flatMap((tag) => (value[tag] ? [{ tag, text: value[tag]! }] : []))
})
</script>

<template>
  <div class="fixed p-12 max-w-full font-sans" :class="[positionClass, dark ? 'text-white' : 'text-slate-900']">
    <Transition :name="alignRight ? 'np-right' : 'np-left'" mode="out-in">
      <div
        v-if="visible && shown"
        :key="trackKey"
        class="flex items-start gap-4 max-w-[60rem]"
        :class="alignRight ? 'flex-row-reverse text-right' : ''"
      >
        <div
          v-if="settings.showArtwork"
          class="artwork h-36 w-36 flex-none rounded overflow-hidden grid place-items-center"
          :class="[alignRight ? 'artwork-right' : 'artwork-left', artUrl ? '' : 'bg-[#28272b]']"
        >
          <img v-if="artUrl" :src="artUrl" alt="" class="h-full w-full object-cover" @error="artFailed = shown.track.artKey" />
          <!-- アートワークがないときは prolink-tools と同じくディスクのアイコン -->
          <svg v-else viewBox="0 0 24 24" class="h-14 w-14 text-[#aaa]" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="12" cy="12" r="10" /><circle cx="12" cy="12" r="3" />
          </svg>
        </div>

        <!-- カードの代わりに、各行の後ろにだけ帯を敷く -->
        <div class="min-w-0 flex flex-col gap-1.5" :class="alignRight ? 'items-end' : 'items-start'">
          <span class="np-label text-sm font-bold uppercase tracking-[0.25em] px-2 py-0.5 rounded-sm bg-brand text-white">
            <RevealText text="Now Playing" :delay="0.2" />
          </span>
          <span class="np-line inline-block max-w-full text-4xl font-semibold leading-snug" :style="lineStyle">
            <MarqueeText :text="shown.track.title || 'Unknown Title'" :reveal-delay="0.4" />
          </span>
          <span class="np-line inline-block max-w-full text-2xl leading-snug" :style="lineStyle">
            <MarqueeText :text="shown.track.artist || 'Unknown Artist'" :reveal-delay="0.6" />
          </span>
          <span
            v-if="tagValues.length"
            class="np-line inline-flex max-w-full text-lg leading-snug flex-wrap gap-x-4"
            :class="alignRight ? 'justify-end' : ''"
            :style="lineStyle"
          >
            <span v-for="(t, i) in tagValues" :key="t.tag" class="inline-flex items-center gap-1.5 whitespace-nowrap">
              <svg viewBox="0 0 24 24" class="h-[1em] w-[1em] flex-none opacity-80" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <!-- feather icons: disc / tag / music / activity -->
                <template v-if="t.tag === 'ALBUM'"><circle cx="12" cy="12" r="10" /><circle cx="12" cy="12" r="3" /></template>
                <template v-else-if="t.tag === 'GENRE'">
                  <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" /><line x1="7" y1="7" x2="7.01" y2="7" />
                </template>
                <template v-else-if="t.tag === 'KEY'"><path d="M9 18V5l12-2v13" /><circle cx="6" cy="18" r="3" /><circle cx="18" cy="16" r="3" /></template>
                <template v-else><polyline points="22 12 18 12 15 21 9 3 6 12 2 12" /></template>
              </svg>
              <RevealText :text="t.text" :delay="0.8 + i * 0.15" />
            </span>
          </span>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.np-line {
  padding: 0 0.28em;
  border-radius: 2px;
}

/* 入れ替え: 新しい曲は横から滑り込み、古い曲はすっと消える */
.np-left-enter-active,
.np-right-enter-active {
  transition: opacity 0.4s ease, transform 0.4s cubic-bezier(0.2, 0.7, 0.3, 1);
}
.np-left-leave-active,
.np-right-leave-active {
  transition: opacity 0.2s ease;
}
.np-left-enter-from {
  opacity: 0;
  transform: translateX(-20px);
}
.np-right-enter-from {
  opacity: 0;
  transform: translateX(20px);
}
.np-left-leave-to,
.np-right-leave-to {
  opacity: 0;
}

/* アートワークは端からワイプで現れる */
.artwork {
  animation: wipe-left 0.6s cubic-bezier(0, 1.07, 0.38, 0.98) both;
}
.artwork-right {
  animation-name: wipe-right;
}
@keyframes wipe-left {
  from { clip-path: inset(0 100% 0 0); }
  to { clip-path: inset(0 0 0 0); }
}
@keyframes wipe-right {
  from { clip-path: inset(0 0 0 100%); }
  to { clip-path: inset(0 0 0 0); }
}
</style>
