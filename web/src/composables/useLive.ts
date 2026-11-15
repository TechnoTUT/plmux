import { reactive, ref } from 'vue'
import type { BeatEvent, HistoryEntry, State } from '../types'

/**
 * サーバーの WebSocket (/ws) に接続し、状態をリアクティブに保持する。切断時は自動で再接続する。
 * アプリ全体で 1 本だけ接続するようモジュールスコープで共有する。
 */
const state = ref<State | null>(null)
const connected = ref(false)
/** プレイヤー番号 → 最後に拍を受け取った時刻 (アニメーション用) */
const lastBeat = reactive<Record<number, { at: number; beatWithinBar: number }>>({})
const historyListeners = new Set<(e: HistoryEntry) => void>()
let started = false

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const ws = new WebSocket(`${proto}//${location.host}/ws`)
  ws.onopen = () => (connected.value = true)
  ws.onclose = () => {
    connected.value = false
    setTimeout(connect, 2000)
  }
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data) as { type: string; data: unknown }
    switch (msg.type) {
      case 'state':
        state.value = msg.data as State
        break
      case 'beat': {
        const b = msg.data as BeatEvent
        lastBeat[b.player] = { at: performance.now(), beatWithinBar: b.beatWithinBar }
        break
      }
      case 'history':
        historyListeners.forEach((l) => l(msg.data as HistoryEntry))
        break
    }
  }
}

export function useLive() {
  if (!started) {
    started = true
    connect()
  }
  return {
    state,
    connected,
    lastBeat,
    onHistory(listener: (e: HistoryEntry) => void) {
      historyListeners.add(listener)
      return () => historyListeners.delete(listener)
    },
  }
}
