<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { HistoryEntry, Session } from '../types'
import { api } from '../api'
import { formatDateTime, formatTime } from '../format'
import { useLive } from '../composables/useLive'

const { onHistory } = useLive()

const sessions = ref<Session[]>([])
const currentId = ref<number | null>(null)
/** 表示中のセッション: 'current' は進行中のセッションに追従 */
const selected = ref<number | 'current' | 'all'>('current')
const entries = ref<HistoryEntry[]>([])
const newName = ref('')
const error = ref<string | null>(null)

const currentSession = computed(() => sessions.value.find((s) => s.id === currentId.value) ?? null)
const viewingLive = computed(
  () => selected.value === 'all' || (selected.value === 'current' && currentId.value !== null)
    || selected.value === currentId.value,
)

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
    selected.value = 'current'
    await loadSessions()
    await loadEntries()
  } catch (e) {
    error.value = String(e)
  }
}

async function endSession() {
  if (!currentSession.value || !confirm(`「${currentSession.value.name}」を終了しますか？`)) return
  await api.endSession()
  await loadSessions()
  await loadEntries()
}

async function remove(e: HistoryEntry) {
  if (!confirm(`履歴から削除しますか？\n${e.artist ?? ''} - ${e.title ?? ''}`)) return
  await api.deleteHistory(e.id)
  entries.value = entries.value.filter((x) => x.id !== e.id)
}

function defaultName() {
  const d = new Date()
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()} セッション`
}

const csvUrl = computed(() => api.historyCsvUrl(selected.value))

let off: (() => void) | undefined
onMounted(async () => {
  await loadSessions()
  await loadEntries()
  off = onHistory((e) => {
    if (viewingLive.value && (selected.value === 'all' || e.sessionId === currentId.value)) {
      entries.value = [e, ...entries.value]
    }
  })
})
onUnmounted(() => off?.())
watch(selected, loadEntries)
</script>

<template>
  <section class="panel">
    <h2>曲履歴</h2>

    <div class="session-bar">
      <template v-if="currentSession">
        <span class="live-dot"></span>
        <strong>{{ currentSession.name }}</strong>
        <span class="muted">{{ formatDateTime(currentSession.startedAt) }} 〜</span>
        <button class="danger" @click="endSession">終了</button>
      </template>
      <template v-else>
        <input v-model="newName" :placeholder="defaultName()" @keyup.enter="startSession" />
        <button class="primary" @click="startSession">セッション開始</button>
      </template>
    </div>
    <p v-if="!currentSession" class="hint muted">
      セッション外で再生された曲も記録されます（「すべて」で表示）。
    </p>

    <div class="toolbar">
      <select v-model="selected">
        <option value="current">進行中のセッション</option>
        <option value="all">すべて</option>
        <option v-for="s in sessions" :key="s.id" :value="s.id">
          {{ s.name }} ({{ s.trackCount }}曲)
        </option>
      </select>
      <a :href="csvUrl" download><button>CSV</button></a>
    </div>

    <ol class="list">
      <li v-for="e in entries" :key="e.id">
        <span class="time num muted">{{ formatTime(e.playedAt) }}</span>
        <span class="deck num">{{ e.player }}</span>
        <span class="what">
          <span class="title">{{ e.title ?? '(タイトルなし)' }}</span>
          <span class="artist muted">{{ e.artist ?? '' }}</span>
        </span>
        <span class="bpm num muted">{{ e.bpm ? e.bpm.toFixed(1) : '' }}</span>
        <button class="del danger" title="削除" @click="remove(e)">×</button>
      </li>
      <li v-if="entries.length === 0" class="empty muted">まだ記録がありません</li>
    </ol>
    <p v-if="error" class="err">{{ error }}</p>
  </section>
</template>

<style scoped>
.session-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.session-bar input { flex: 1; min-width: 160px; }
.session-bar .danger { margin-left: auto; }
.live-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--onair); animation: pulse 1.6s infinite; }
@keyframes pulse { 50% { opacity: 0.3; } }
.hint { font-size: 12px; margin: 6px 0 0; }
.toolbar { display: flex; gap: 8px; margin: 14px 0 8px; }
.toolbar select { flex: 1; min-width: 0; }
.list { list-style: none; margin: 0; padding: 0; max-height: 420px; overflow-y: auto; }
.list li {
  display: grid; grid-template-columns: auto auto 1fr auto auto; align-items: center; gap: 10px;
  padding: 7px 0; border-bottom: 1px solid var(--border);
}
.time { font-size: 13px; }
.deck { font-size: 12px; font-weight: 700; background: var(--surface-2); border-radius: 4px; padding: 1px 6px; }
.what { min-width: 0; display: flex; flex-direction: column; }
.title, .artist { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.artist { font-size: 12px; }
.bpm { font-size: 12px; }
.del { padding: 2px 8px; border: none; background: transparent; color: var(--muted); }
.empty { display: block !important; text-align: center; padding: 20px 0 !important; border: none !important; }
.err { color: var(--onair); font-size: 13px; }
</style>
