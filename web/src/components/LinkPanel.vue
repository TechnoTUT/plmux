<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { LinkMode, LinkStatus } from '../types'
import { api } from '../api'

defineProps<{ status: LinkStatus | null }>()

const latencyMs = ref(1)
const alignBars = ref(true)
const busy = ref(false)
const error = ref<string | null>(null)

const modes: { value: LinkMode; label: string; help: string }[] = [
  { value: 'OFF', label: 'OFF', help: 'Ableton Link を使わない' },
  { value: 'MONITOR', label: 'モニター', help: 'Link セッションの状態を表示するだけ' },
  { value: 'FOLLOW', label: 'CDJ に追従', help: 'Link のテンポと拍を DJ Link のマスターに合わせる' },
]

onMounted(async () => {
  try {
    const s = await api.getLink()
    latencyMs.value = s.latencyMs
    alignBars.value = s.alignBars
  } catch (e) {
    error.value = String(e)
  }
})

async function update(patch: Parameters<typeof api.updateLink>[0]) {
  busy.value = true
  error.value = null
  try {
    const s = await api.updateLink(patch)
    latencyMs.value = s.latencyMs
    alignBars.value = s.alignBars
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel">
    <h2>Ableton Link</h2>

    <div class="modes">
      <button
        v-for="m in modes"
        :key="m.value"
        :class="{ primary: status?.mode === m.value }"
        :disabled="busy"
        :title="m.help"
        @click="update({ mode: m.value })"
      >
        {{ m.label }}
      </button>
    </div>

    <dl v-if="status && status.mode !== 'OFF'" class="stats">
      <div>
        <dt>Carabiner</dt>
        <dd :class="status.connected ? 'ok' : 'ng'">
          {{ status.connected ? '接続中' : '未接続' }}<span v-if="status.embedded" class="muted"> (内蔵)</span>
        </dd>
      </div>
      <div><dt>Link BPM</dt><dd class="num">{{ status.connected ? status.bpm.toFixed(2) : '--' }}</dd></div>
      <div><dt>ピア数</dt><dd class="num">{{ status.connected ? status.peers : '--' }}</dd></div>
      <div v-if="status.mode === 'FOLLOW'">
        <dt>追従先 BPM</dt><dd class="num">{{ status.targetBpm?.toFixed(2) ?? '--' }}</dd>
      </div>
    </dl>
    <p v-if="status?.error" class="err">{{ status.error }}</p>

    <div v-if="status?.mode === 'FOLLOW'" class="settings">
      <label>
        遅延補正
        <input
          type="number" step="1" min="-100" max="100" class="num"
          :value="latencyMs"
          @change="update({ latencyMs: Number(($event.target as HTMLInputElement).value) })"
        /> ms
      </label>
      <label>
        <input type="checkbox" :checked="alignBars" @change="update({ alignBars: ($event.target as HTMLInputElement).checked })" />
        小節も揃える
      </label>
    </div>
    <p v-if="error" class="err">{{ error }}</p>
  </section>
</template>

<style scoped>
.modes { display: flex; gap: 6px; flex-wrap: wrap; }
.stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 10px; margin: 14px 0 0; }
.stats dt { font-size: 12px; color: var(--muted); }
.stats dd { margin: 2px 0 0; font-size: 18px; font-weight: 700; }
.ok { color: var(--play); }
.ng { color: var(--onair); }
.settings { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; margin-top: 14px; font-size: 14px; }
.settings input[type='number'] { width: 70px; margin: 0 4px; }
.err { color: var(--onair); font-size: 13px; margin: 10px 0 0; }
</style>
