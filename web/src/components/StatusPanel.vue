<script setup lang="ts">
import { computed } from 'vue'
import type { State } from '../types'

const props = defineProps<{ state: State | null; connected: boolean }>()

const status = computed(() => {
  if (!props.connected) return { label: 'Offline', cls: 'pill-live' }
  if (!props.state?.djLinkOnline) return { label: 'Searching', cls: 'pill-warn' }
  return { label: 'Online', cls: 'pill-ok' }
})
const onAir = computed(() => props.state?.players.filter((p) => p.onAir).map((p) => p.number) ?? [])
</script>

<template>
  <section class="card space-y-4">
    <h2 class="card-title">DJ Link Status</h2>
    <div class="flex items-center justify-between">
      <span class="text-slate-600 dark:text-slate-300 font-medium text-sm">Status</span>
      <span class="pill" :class="status.cls">{{ status.label }}</span>
    </div>
    <div class="space-y-2 border-t border-slate-100 dark:border-slate-800 pt-3">
      <div class="kv"><span>Master Deck</span><span>{{ state?.masterPlayer ?? '-' }}</span></div>
      <div class="kv"><span>Master BPM</span><span>{{ state?.masterPlayer ? state.masterBpm.toFixed(2) : '-' }}</span></div>
      <div class="kv"><span>On Air</span><span>{{ onAir.length ? onAir.join(', ') : '-' }}</span></div>
      <div class="kv"><span>Decks</span><span>{{ state?.players.length ?? 0 }}</span></div>
    </div>
    <div v-if="state?.otherDevices.length" class="space-y-2 border-t border-slate-100 dark:border-slate-800 pt-3">
      <h3 class="text-xs font-medium text-slate-600 dark:text-slate-400">Other Devices</h3>
      <div v-for="d in state.otherDevices" :key="d.number" class="kv">
        <span>#{{ d.number }} {{ d.name }}</span><span>{{ d.address }}</span>
      </div>
    </div>
  </section>
</template>
