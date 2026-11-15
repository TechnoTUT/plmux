<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { HistoryEntry } from '../types'
import { api } from '../api'
import { formatTime } from '../format'
import { useLive } from '../composables/useLive'

const emit = defineEmits<{ 'show-all': [] }>()
const { onHistory } = useLive()
const entries = ref<HistoryEntry[]>([])
const LIMIT = 6

let off: (() => void) | undefined
onMounted(async () => {
  try {
    entries.value = await api.getHistory('all', LIMIT)
  } catch {
    // 履歴が取れなくても他の表示には影響させない
  }
  off = onHistory((e) => (entries.value = [e, ...entries.value].slice(0, LIMIT)))
})
onUnmounted(() => off?.())
</script>

<template>
  <section class="card space-y-3">
    <div class="flex items-center justify-between">
      <h2 class="card-title">Recent Tracks</h2>
      <button class="text-xs font-semibold text-brand hover:text-brand-hover" @click="emit('show-all')">View all</button>
    </div>
    <ol class="space-y-2">
      <li v-for="e in entries" :key="e.id" class="flex items-center gap-3 min-w-0">
        <span class="text-[11px] font-mono tabular-nums text-slate-400 w-10 flex-none">{{ formatTime(e.playedAt) }}</span>
        <div class="min-w-0">
          <p class="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">{{ e.title ?? '(タイトルなし)' }}</p>
          <p class="text-xs text-slate-500 dark:text-slate-400 truncate">{{ e.artist ?? '' }}</p>
        </div>
      </li>
      <li v-if="entries.length === 0" class="text-sm text-slate-400 text-center py-4">まだ記録がありません</li>
    </ol>
  </section>
</template>
