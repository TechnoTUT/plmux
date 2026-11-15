const pad = (n: number) => n.toString().padStart(2, '0')

export function formatDuration(totalSec: number): string {
  if (!Number.isFinite(totalSec)) return '--:--'
  if (totalSec < 0) totalSec = 0
  const m = Math.floor(totalSec / 60)
  const s = Math.floor(totalSec % 60)
  return `${m}:${pad(s)}`
}

/** 24-hour "HH:MM" */
export function formatTime(epochMs: number): string {
  const d = new Date(epochMs)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** "M/D HH:MM" */
export function formatDateTime(epochMs: number): string {
  const d = new Date(epochMs)
  return `${d.getMonth() + 1}/${d.getDate()} ${formatTime(epochMs)}`
}

export function formatPitch(pct: number): string {
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
}
