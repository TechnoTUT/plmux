<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { ExpressionsStatus } from '../types'
import { api } from '../api'
import { formatDateTime } from '../format'

/** タブが表示されている間だけ取り直す */
const props = defineProps<{ active: boolean }>()

const status = ref<ExpressionsStatus | null>(null)
const fetchError = ref<string | null>(null)
let timer: number | undefined

async function refresh() {
  try {
    status.value = await api.getExpressions()
    fetchError.value = null
  } catch (e) {
    fetchError.value = String(e)
  }
}

watch(
  () => props.active,
  (active) => {
    clearInterval(timer)
    if (!active) return
    refresh()
    timer = window.setInterval(refresh, 2000)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearInterval(timer))

const lines = computed(() => status.value?.source?.replace(/\n$/, '').split('\n') ?? [])

/** エラーの "(line 12)" や "expressions.clj:12:1" から、ソースの中で目立たせる行 */
const errorLines = computed(() => {
  const out = new Set<number>()
  const texts = [status.value?.error, ...(status.value?.hooks.map((h) => h.lastError) ?? [])]
  for (const t of texts) {
    if (!t) continue
    for (const m of t.matchAll(/\(line (\d+)\)|\.clj:(\d+):\d+/g)) out.add(Number(m[1] ?? m[2]))
  }
  return out
})

function pill() {
  const s = status.value
  if (!s) return { label: '-', cls: 'pill-off' }
  if (!s.exists) return { label: 'No File', cls: 'pill-off' }
  if (s.error) return { label: 'Error', cls: 'pill-live' }
  if (s.hooks.some((h) => h.errors > 0)) return { label: 'Running', cls: 'pill-warn' }
  return { label: 'Running', cls: 'pill-ok' }
}
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <div class="space-y-6">
      <!-- Status -->
      <section class="card space-y-4">
        <h2 class="card-title">Expressions</h2>
        <div class="flex items-center justify-between">
          <span class="text-slate-600 dark:text-slate-300 font-medium text-sm">Clojure</span>
          <span class="pill" :class="pill().cls">{{ pill().label }}</span>
        </div>
        <div class="space-y-2 border-t border-slate-100 dark:border-slate-800 pt-3">
          <div class="kv"><span>Loaded</span><span>{{ status?.loadedAt ? formatDateTime(status.loadedAt) : '-' }}</span></div>
        </div>
        <div>
          <span class="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">File</span>
          <code class="block text-xs font-mono break-all text-slate-700 dark:text-slate-300 bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 rounded-lg px-3 py-2">{{ status?.path ?? '-' }}</code>
        </div>
        <p
          v-if="status?.error"
          class="text-xs font-mono break-words text-rose-600 dark:text-rose-400 bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/60 rounded-xl px-3 py-2"
        >{{ status.error }}</p>
        <p class="text-xs text-slate-400">
          Expressions can only be edited in this file on the server (the GUI has no authentication).
          Changes are picked up within a few seconds. See the README for the hooks and values you can use.
        </p>
        <p v-if="fetchError" class="text-sm text-rose-600">{{ fetchError }}</p>
      </section>

      <!-- Hooks -->
      <section class="card space-y-3">
        <h2 class="card-title">Hooks</h2>
        <div v-if="status" class="divide-y divide-slate-100 dark:divide-slate-800">
          <div v-for="h in status.hooks" :key="h.name" class="py-2 first:pt-0 last:pb-0">
            <div class="flex items-center justify-between gap-3 text-sm">
              <span class="font-mono" :class="h.defined ? 'text-slate-800 dark:text-slate-100' : 'text-slate-400 dark:text-slate-600'">
                {{ h.name }}<span v-if="h.intervalMs" class="ml-1.5 text-slate-400">{{ h.intervalMs }}ms</span>
              </span>
              <span v-if="h.defined" class="font-mono tabular-nums text-xs text-slate-500 dark:text-slate-400">
                {{ h.runs }} runs<span v-if="h.errors" class="text-rose-600 dark:text-rose-400"> · {{ h.errors }} errors</span>
              </span>
            </div>
            <p v-if="h.lastError" class="mt-1 text-xs font-mono break-words text-rose-600 dark:text-rose-400">
              {{ h.lastErrorAt ? formatDateTime(h.lastErrorAt) + ' ' : '' }}{{ h.lastError }}
            </p>
          </div>
        </div>
      </section>
    </div>

    <!-- Source -->
    <section class="card lg:col-span-2 space-y-4 self-start min-w-0">
      <h2 class="card-title">Source</h2>
      <div
        v-if="lines.length"
        class="overflow-x-auto rounded-xl bg-slate-50 dark:bg-slate-950 border border-slate-200 dark:border-slate-800 py-3 text-xs font-mono leading-relaxed"
      >
        <!-- エラー行の背景を、横にスクロールした先まで伸ばす -->
        <div class="min-w-max">
          <div
            v-for="(line, i) in lines"
            :key="i"
            class="flex"
            :class="errorLines.has(i + 1) ? 'bg-rose-100/70 dark:bg-rose-950/50' : ''"
          >
            <span class="select-none w-12 flex-none pr-4 text-right text-slate-400 dark:text-slate-600 tabular-nums">{{ i + 1 }}</span>
            <span class="whitespace-pre pr-4 text-slate-800 dark:text-slate-200">{{ line }}</span>
          </div>
        </div>
      </div>
      <div v-else class="text-center py-12 space-y-2">
        <p class="font-semibold text-slate-700 dark:text-slate-200">No expressions</p>
        <p class="text-sm text-slate-500 dark:text-slate-400">
          Create <code class="font-mono">{{ status?.path ?? 'expressions.clj' }}</code> on the server to add your own
          behavior in Clojure, the same way as Beat Link Trigger expressions.
        </p>
      </div>
    </section>
  </div>
</template>
