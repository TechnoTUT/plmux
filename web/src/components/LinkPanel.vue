<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { LinkMode, LinkStatus } from '../types'
import { api } from '../api'

const props = defineProps<{ status: LinkStatus | null }>()

const latencyMs = ref(1)
const alignBars = ref(true)
const busy = ref(false)
const error = ref<string | null>(null)

const modes: { value: LinkMode; label: string; help: string }[] = [
  { value: 'OFF', label: 'Off', help: 'Do not use Ableton Link.' },
  { value: 'MONITOR', label: 'Monitor', help: 'Join the Link session and show its tempo and peer count without changing anything.' },
  { value: 'FOLLOW', label: 'Follow CDJ Master', help: 'Make the Link tempo and beat grid (and bars) follow the DJ Link tempo master. Use this to sync Ableton Live, lighting or VJ software to the CDJs.' },
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

function pill() {
  const s = props.status
  if (!s || s.mode === 'OFF') return { label: 'Off', cls: 'pill-off' }
  return s.connected ? { label: 'Connected', cls: 'pill-ok' } : { label: 'Connecting', cls: 'pill-warn' }
}
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <div class="space-y-6">
      <!-- Status -->
      <section class="card space-y-4">
        <h2 class="card-title">Link Status</h2>
        <div class="flex items-center justify-between">
          <span class="text-slate-600 dark:text-slate-300 font-medium text-sm">Carabiner</span>
          <span class="pill" :class="pill().cls">{{ pill().label }}</span>
        </div>
        <div class="space-y-2 border-t border-slate-100 dark:border-slate-800 pt-3">
          <div class="kv"><span>Link BPM</span><span>{{ status?.connected ? status.bpm.toFixed(2) : '-' }}</span></div>
          <div class="kv"><span>Peers</span><span>{{ status?.connected ? status.peers : '-' }}</span></div>
          <div class="kv"><span>Target BPM</span><span>{{ status?.targetBpm != null ? status.targetBpm.toFixed(2) : '-' }}</span></div>
          <div class="kv"><span>Carabiner</span><span class="font-sans!">{{ status?.connected ? (status.embedded ? 'Embedded' : 'External') : '-' }}</span></div>
        </div>
        <p v-if="status?.error" class="text-xs text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/60 rounded-xl px-3 py-2">{{ status.error }}</p>
      </section>

      <!-- Options -->
      <section class="card space-y-4">
        <h2 class="card-title">Follow Options</h2>
        <div>
          <label class="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Latency Compensation (ms)</label>
          <input
            type="number" step="1" min="-100" max="100" class="input font-mono"
            :value="latencyMs"
            @change="update({ latencyMs: Number(($event.target as HTMLInputElement).value) })"
          />
          <p class="text-xs text-slate-400 mt-1">Delay before beat packets arrive. Increase it if Link sounds late.</p>
        </div>
        <label class="flex items-center justify-between cursor-pointer pt-3 border-t border-slate-100 dark:border-slate-800">
          <div>
            <span class="text-sm font-medium text-slate-700 dark:text-slate-300 block">Align Bars</span>
            <span class="text-xs text-slate-400 block">Line up the downbeat of each bar, not just beats</span>
          </div>
          <input type="checkbox" class="checkbox" :checked="alignBars" @change="update({ alignBars: ($event.target as HTMLInputElement).checked })" />
        </label>
      </section>
    </div>

    <!-- Mode -->
    <section class="card lg:col-span-2 space-y-4">
      <h2 class="card-title">Sync Mode</h2>
      <div class="grid gap-3">
        <button
          v-for="m in modes"
          :key="m.value"
          :disabled="busy"
          class="text-left p-4 rounded-2xl border-2 transition disabled:opacity-60"
          :class="status?.mode === m.value
            ? 'border-brand bg-rose-50/60 dark:bg-brand-dim/20'
            : 'border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700'"
          @click="update({ mode: m.value })"
        >
          <div class="flex items-center gap-3">
            <span
              class="h-4 w-4 rounded-full border-2 grid place-items-center flex-none"
              :class="status?.mode === m.value ? 'border-brand' : 'border-slate-300 dark:border-slate-600'"
            >
              <span v-if="status?.mode === m.value" class="h-2 w-2 rounded-full bg-brand" />
            </span>
            <span class="font-semibold text-slate-900 dark:text-slate-100">{{ m.label }}</span>
          </div>
          <p class="text-sm text-slate-500 dark:text-slate-400 mt-1 ml-7">{{ m.help }}</p>
        </button>
      </div>
      <p v-if="error" class="text-sm text-rose-600">{{ error }}</p>
    </section>
  </div>
</template>
