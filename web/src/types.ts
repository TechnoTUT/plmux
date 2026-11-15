// server/src/main/java/plmux/Model.java と対応する型

export interface Track {
  rekordboxId: number
  title: string | null
  artist: string | null
  album: string | null
  genre: string | null
  key: string | null
  durationSec: number
  bpm: number
  sourcePlayer: number
  sourceSlot: string | null
  artKey: string
  waveformKey: string | null
}

export interface Player {
  number: number
  model: string
  address: string
  playing: boolean
  onAir: boolean
  master: boolean
  synced: boolean
  bpm: number
  pitchPercent: number
  beat: number
  beatWithinBar: number
  positionMs: number
  trackLoaded: boolean
  track: Track | null
}

export interface Device {
  number: number
  name: string
  address: string
}

export type LinkMode = 'OFF' | 'MONITOR' | 'FOLLOW'

export interface LinkStatus {
  mode: LinkMode
  connected: boolean
  embedded: boolean
  peers: number
  bpm: number
  targetBpm: number | null
  error: string | null
}

export interface State {
  demo: boolean
  djLinkOnline: boolean
  masterPlayer: number | null
  masterBpm: number
  players: Player[]
  otherDevices: Device[]
  warnings: string[]
  link: LinkStatus
}

export interface BeatEvent {
  player: number
  beatWithinBar: number
  bpm: number
  master: boolean
}

export interface Session {
  id: number
  name: string
  startedAt: number
  endedAt: number | null
  trackCount: number
}

export interface HistoryEntry {
  id: number
  sessionId: number | null
  playedAt: number
  player: number
  rekordboxId: number
  title: string | null
  artist: string | null
  album: string | null
  genre: string | null
  key: string | null
  bpm: number
  durationSec: number
}

export interface LinkSettings {
  status: LinkStatus
  latencyMs: number
  alignBars: boolean
}

export type WaveformStyle = 'BLUE' | 'RGB' | 'THREE_BAND'

/** hotCue が 0 ならメモリーポイント、endMs があればループ */
export interface Cue {
  timeMs: number
  endMs: number | null
  hotCue: number
  color: string | null
  comment: string | null
}

/** /api/players/{n}/waveform のレスポンス (server/src/main/java/plmux/Model.java の Waveform) */
export interface WaveformResponse {
  key: string
  style: WaveformStyle
  durationMs: number
  preview: { segments: number; maxHeight: number; stride: number; data: string } | null
  detail: { frames: number; stride: number; data: string } | null
  beatTimesMs: number[]
  beatsWithinBar: number[]
  cues: Cue[]
}

/** Base64 を展開済みの波形 */
export interface Waveform {
  key: string
  style: WaveformStyle
  durationMs: number
  preview: { segments: number; maxHeight: number; stride: number; bytes: Uint8Array } | null
  detail: { frames: number; stride: number; bytes: Uint8Array } | null
  beatTimesMs: number[]
  beatsWithinBar: number[]
  cues: Cue[]
}
