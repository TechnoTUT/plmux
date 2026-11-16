import type { ExpressionsStatus, HistoryEntry, LinkMode, LinkSettings, OverlaySettings, Session, Settings, WaveformResponse, WaveformStyle } from './types'

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`${method} ${url}: ${res.status} ${await res.text()}`)
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

export interface SettingsPatch {
  waveformStyle?: WaveformStyle
  linkStartupMode?: LinkMode
  overlay?: Partial<OverlaySettings>
}

export const api = {
  getLink: () => request<LinkSettings>('GET', '/api/link'),
  updateLink: (patch: { mode?: LinkMode; latencyMs?: number; alignBars?: boolean }) =>
    request<LinkSettings>('PUT', '/api/link', patch),

  getExpressions: () => request<ExpressionsStatus>('GET', '/api/expressions'),

  getSettings: () => request<Settings>('GET', '/api/settings'),
  updateSettings: (patch: SettingsPatch) => request<Settings>('PUT', '/api/settings', patch),

  getSessions: () => request<{ current: number | null; sessions: Session[] }>('GET', '/api/sessions'),
  startSession: (name: string) => request<Session>('POST', '/api/sessions', { name }),
  endSession: () => request<void>('POST', '/api/sessions/end'),

  getHistory: (session: number | 'all' | 'current', limit = 500) =>
    request<HistoryEntry[]>('GET', `/api/history?session=${session}&limit=${limit}`),
  deleteHistory: (id: number) => request<void>('DELETE', `/api/history/${id}`),
  historyCsvUrl: (session: number | 'all' | 'current') => `/api/history.csv?session=${session}`,

  getWaveform: (player: number, key: string) =>
    request<WaveformResponse>('GET', `/api/players/${player}/waveform?k=${encodeURIComponent(key)}`),

  artworkUrl: (player: number, artKey: string) =>
    `/api/players/${player}/artwork?k=${encodeURIComponent(artKey)}`,
}
