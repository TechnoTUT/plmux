<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { Player } from '../types'
import { api } from '../api'
import { formatDuration, formatPitch } from '../format'
import { useLive } from '../composables/useLive'

const props = defineProps<{ player: Player }>()
const { lastBeat } = useLive()

// 拍インジケーターを光らせるため、受信した拍から 150ms だけ "flash" にする
const now = ref(performance.now())
let raf = 0
const tick = () => {
  now.value = performance.now()
  raf = requestAnimationFrame(tick)
}
raf = requestAnimationFrame(tick)
onUnmounted(() => cancelAnimationFrame(raf))

const beatInfo = computed(() => lastBeat[props.player.number])
const flashing = computed(() => !!beatInfo.value && now.value - beatInfo.value.at < 150)
const currentBeat = computed(() => beatInfo.value?.beatWithinBar || props.player.beatWithinBar)

const track = computed(() => props.player.track)
const progress = computed(() => {
  const d = track.value?.durationSec
  return d ? Math.min(100, (props.player.positionMs / 1000 / d) * 100) : 0
})
const artFailed = ref<string | null>(null)
const artUrl = computed(() =>
  track.value && artFailed.value !== track.value.artKey
    ? api.artworkUrl(props.player.number, track.value.artKey)
    : null,
)
</script>

<template>
  <article class="card" :class="{ playing: player.playing, onair: player.onAir, master: player.master }">
    <header>
      <span class="deck num">{{ player.number }}</span>
      <span class="model muted">{{ player.model }}</span>
      <span class="badges">
        <span class="badge play" :class="{ on: player.playing }">PLAY</span>
        <span class="badge onair" :class="{ on: player.onAir }">ON AIR</span>
        <span class="badge master" :class="{ on: player.master }">MASTER</span>
        <span class="badge sync" :class="{ on: player.synced }">SYNC</span>
      </span>
    </header>

    <div class="body">
      <div class="art">
        <img v-if="artUrl" :src="artUrl" alt="" @error="artFailed = track?.artKey ?? null" />
        <div v-else class="art-empty">♪</div>
      </div>
      <div class="meta">
        <template v-if="track">
          <div class="title" :title="track.title ?? ''">{{ track.title ?? '(タイトルなし)' }}</div>
          <div class="artist" :title="track.artist ?? ''">{{ track.artist ?? '(アーティスト不明)' }}</div>
          <div class="sub muted">
            <span v-if="track.key">{{ track.key }}</span>
            <span v-if="track.genre">{{ track.genre }}</span>
            <span v-if="track.sourceSlot">{{ track.sourcePlayer }}: {{ track.sourceSlot }}</span>
          </div>
        </template>
        <div v-else-if="player.trackLoaded" class="title muted">メタデータ取得中…</div>
        <div v-else class="title muted">トラック未ロード</div>
      </div>
    </div>

    <div class="tempo">
      <div class="bpm num">{{ player.bpm > 0 ? player.bpm.toFixed(1) : '--.-' }}<small>BPM</small></div>
      <div class="pitch num muted">{{ formatPitch(player.pitchPercent) }}</div>
      <div class="beats" :class="{ flash: flashing }">
        <span v-for="b in 4" :key="b" :class="{ active: currentBeat === b }"></span>
      </div>
    </div>

    <div class="progress">
      <div class="bar"><div class="fill" :style="{ width: progress + '%' }"></div></div>
      <div class="times num muted">
        <span>{{ formatDuration(player.positionMs / 1000) }}</span>
        <span>{{ formatDuration(track?.durationSec ?? 0) }}</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  transition: border-color 0.2s;
}
.card.onair { border-color: color-mix(in srgb, var(--onair) 55%, var(--border)); }
.card.master { box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--master) 40%, transparent); }

header { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.deck {
  font-size: 20px; font-weight: 800; width: 32px; height: 32px; border-radius: 6px;
  background: var(--surface-2); display: grid; place-items: center;
}
.model { font-size: 12px; }
.badges { margin-left: auto; display: flex; gap: 4px; flex-wrap: wrap; }

.body { display: flex; gap: 12px; min-width: 0; }
.art { width: 72px; height: 72px; flex: none; border-radius: 6px; overflow: hidden; background: var(--surface-2); }
.art img { width: 100%; height: 100%; object-fit: cover; display: block; }
.art-empty { width: 100%; height: 100%; display: grid; place-items: center; color: var(--muted); font-size: 24px; }
.meta { min-width: 0; display: flex; flex-direction: column; gap: 2px; justify-content: center; }
.title { font-weight: 700; font-size: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artist { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sub { font-size: 12px; display: flex; gap: 10px; flex-wrap: wrap; }

.tempo { display: flex; align-items: baseline; gap: 12px; }
.bpm { font-size: 30px; font-weight: 800; line-height: 1; }
.bpm small { font-size: 11px; font-weight: 600; color: var(--muted); margin-left: 4px; }
.pitch { font-size: 13px; }
.beats { margin-left: auto; display: flex; gap: 5px; align-self: center; }
.beats span { width: 14px; height: 14px; border-radius: 3px; background: var(--surface-2); border: 1px solid var(--border); }
.beats span.active { background: var(--muted); }
.beats.flash span.active { background: var(--accent); border-color: var(--accent); }
.card.master .beats.flash span.active { background: var(--master); border-color: var(--master); }

.bar { height: 4px; background: var(--surface-2); border-radius: 2px; overflow: hidden; }
.fill { height: 100%; background: var(--accent); }
.card.onair .fill { background: var(--onair); }
.times { display: flex; justify-content: space-between; font-size: 12px; margin-top: 4px; }
</style>
