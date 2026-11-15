export function formatDuration(totalSec: number): string {
  if (!Number.isFinite(totalSec)) return '--:--'
  if (totalSec < 0) totalSec = 0
  const m = Math.floor(totalSec / 60)
  const s = Math.floor(totalSec % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

export function formatTime(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString('ja-JP', { hour: '2-digit', minute: '2-digit' })
}

export function formatDateTime(epochMs: number): string {
  return new Date(epochMs).toLocaleString('ja-JP', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

export function formatPitch(pct: number): string {
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}
