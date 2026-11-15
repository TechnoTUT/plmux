<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { HistoryEntry, Session } from '../types'
import { api } from '../api'
import { formatDateTime, formatDuration, formatTime } from '../format'
import { useLive } from '../composables/useLive'

const { onHistory } = useLive()

const sessions = ref<Session[]>([])
const currentId = ref<number | null>(null)
/** 表示中のセッション: 'current' は進行中のセッションに追従 */
const selected = ref<number | 'current' | 'all'>('all')
const entries = ref<HistoryEntry[]>([])
const newName = ref('')
const error = ref<string | null>(null)

const currentSession = computed(() => sessions.value.find((s) => s.id === currentId.value) ?? null)
const selectedLabel = computed(() => {
  if (selected.value === 'all') return 'All Tracks'
  if (selected.value === 'current') return currentSession.value?.name ?? 'Current Session'
  return sessions.value.find((s) => s.id === selected.value)?.name ?? ''
})

async function loadSessions() {
  const res = await api.getSessions()
  sessions.value = res.sessions
  currentId.value = res.current
}

async function loadEntries() {
  try {
    entries.value = await api.getHistory(selected.value)
  } catch (e) {
    error.value = String(e)
  }
}

async function startSession() {
  const name = newName.value.trim() || defaultName()
  try {
    await api.startSession(name)
    newName.value = ''
    await loadSessions()
    selected.value = 'current'
    await loadEntries()
  } catch (e) {
    error.value = String(e)
  }
}

async function endSession() {
  if (!currentSession.value || !confirm(`End session "${currentSession.value.name}"?`)) return
  await api.endSession()
  await loadSessions()
  await loadEntries()
}

async function remove(e: HistoryEntry) {
  if (!confirm(`Delete this entry from the history?\n${e.artist ?? ''} - ${e.title ?? ''}`)) return
  await api.deleteHistory(e.id)
  entries.value = entries.value.filter((x) => x.id !== e.id)
  await loadSessions()
}

function defaultName() {
  const d = new Date()
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()} Session`
}

function matchesView(e: HistoryEntry) {
  if (selected.value === 'all') return true
  const id = selected.value === 'current' ? currentId.value : selected.value
  return id !== null && e.sessionId === id
}

let off: (() => void) | undefined
onMounted(async () => {
  await loadSessions()
  await loadEntries()
  off = onHistory((e) => {
    if (matchesView(e)) entries.value = [e, ...entries.value]
    const s = sessions.value.find((x) => x.id === e.sessionId)
    if (s) s.trackCount++
  })
})
onUnmounted(() => off?.())
watch(selected, loadEntries)
</script>

<template>
  <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
    <!-- Left: Session -->
    <div class="space-y-6">
      <section class="card space-y-4">
        <h2 class="card-title">Session</h2>
        <div class="flex items-center justify-between">
          <span class="text-slate-600 dark:text-slate-300 font-medium text-sm">Status</span>
          <span class="pill flex items-center gap-1.5" :class="currentSession ? 'pill-live' : 'pill-off'">
            <span v-if="currentSession" class="h-1.5 w-1.5 rounded-full bg-rose-500 animate-pulse" />
            {{ currentSession ? 'Recording' : 'No Session' }}
          </span>
        </div>

        <template v-if="currentSession">
          <div class="space-y-2 border-t border-slate-100 dark:border-slate-800 pt-3">
            <div class="kv"><span>Name</span><span class="font-sans!">{{ currentSession.name }}</span></div>
            <div class="kv"><span>Started</span><span>{{ formatDateTime(currentSession.startedAt) }}</span></div>
            <div class="kv"><span>Tracks</span><span>{{ currentSession.trackCount }}</span></div>
          </div>
          <button class="btn-danger w-full" @click="endSession">End Session</button>
        </template>
        <template v-else>
          <div class="border-t border-slate-100 dark:border-slate-800 pt-3 space-y-3">
            <div>
              <label class="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Session Name</label>
              <input v-model="newName" class="input" :placeholder="defaultName()" @keyup.enter="startSession" />
            </div>
            <button class="btn-primary w-full" @click="startSession">Start Session</button>
            <p class="text-xs text-slate-400">Start a session for each event to export its set list. Tracks played outside a session are still logged under All Tracks.</p>
          </div>
        </template>
      </section>

      <section class="card space-y-3">
        <h2 class="card-title">Past Sessions</h2>
        <button
          v-for="s in sessions"
          :key="s.id"
          class="w-full text-left px-3 py-2 rounded-xl border transition"
          :class="selected === s.id || (selected === 'current' && s.id === currentId)
            ? 'border-brand bg-rose-50/60 dark:bg-brand-dim/20'
            : 'border-slate-200 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/60'"
          @click="selected = s.id"
        >
          <div class="flex items-center justify-between gap-2">
            <span class="text-sm font-semibold truncate">{{ s.name }}</span>
            <span class="text-xs font-mono tabular-nums text-slate-400 flex-none">{{ s.trackCount }} tracks</span>
          </div>
          <p class="text-[11px] text-slate-400 font-mono">
            {{ formatDateTime(s.startedAt) }}<template v-if="s.endedAt"> – {{ formatTime(s.endedAt) }}</template>
            <span v-else class="text-rose-500 font-sans font-semibold"> · LIVE</span>
          </p>
        </button>
        <p v-if="sessions.length === 0" class="text-sm text-slate-400 text-center py-2">No sessions yet</p>
      </section>
    </div>

    <!-- Right: Track list -->
    <section class="card lg:col-span-2 space-y-4 min-w-0">
      <div class="flex flex-wrap items-center gap-3">
        <h2 class="card-title">Played Tracks</h2>
        <span class="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{{ selectedLabel }}</span>
        <div class="ml-auto flex gap-2">
          <select v-model="selected" class="input w-auto!">
            <option value="all">All Tracks</option>
            <option v-if="currentId !== null" value="current">Current Session</option>
            <option v-for="s in sessions" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <a :href="api.historyCsvUrl(selected)" download class="btn flex items-center gap-1.5">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" /></svg>
            CSV
          </a>
        </div>
      </div>

      <div class="overflow-x-auto -mx-5 px-5">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-[10px] uppercase tracking-wider text-slate-400 border-b border-slate-200 dark:border-slate-800">
              <th class="py-2 pr-3 font-semibold">Time</th>
              <th class="py-2 pr-3 font-semibold">Deck</th>
              <th class="py-2 pr-3 font-semibold">Track</th>
              <th class="py-2 pr-3 font-semibold text-right">BPM</th>
              <th class="py-2 pr-3 font-semibold">Key</th>
              <th class="py-2 pr-3 font-semibold text-right">Length</th>
              <th class="py-2"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in entries" :key="e.id" class="border-b border-slate-100 dark:border-slate-800/60 group">
              <td class="py-2 pr-3 font-mono tabular-nums text-slate-500 dark:text-slate-400">{{ formatTime(e.playedAt) }}</td>
              <td class="py-2 pr-3">
                <span class="inline-grid place-items-center h-6 w-6 rounded-lg bg-slate-100 dark:bg-slate-800 text-xs font-bold tabular-nums">{{ e.player }}</span>
              </td>
              <td class="py-2 pr-3 max-w-0 w-full">
                <p class="font-semibold text-slate-800 dark:text-slate-100 truncate">{{ e.title ?? '(untitled)' }}</p>
                <p class="text-xs text-slate-500 dark:text-slate-400 truncate">{{ e.artist ?? '' }}</p>
              </td>
              <td class="py-2 pr-3 font-mono tabular-nums text-right">{{ e.bpm ? e.bpm.toFixed(1) : '' }}</td>
              <td class="py-2 pr-3 font-mono">{{ e.key ?? '' }}</td>
              <td class="py-2 pr-3 font-mono tabular-nums text-right text-slate-500 dark:text-slate-400">{{ formatDuration(e.durationSec) }}</td>
              <td class="py-2 text-right">
                <button
                  class="opacity-0 group-hover:opacity-100 focus:opacity-100 p-1 rounded-lg text-slate-400 hover:text-rose-600 hover:bg-rose-50 dark:hover:bg-rose-950/40 transition"
                  title="Delete" @click="remove(e)"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
                </button>
              </td>
            </tr>
            <tr v-if="entries.length === 0">
              <td colspan="7" class="text-center text-slate-400 py-10">No tracks logged yet</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="error" class="text-sm text-rose-600">{{ error }}</p>
    </section>
  </div>
</template>
