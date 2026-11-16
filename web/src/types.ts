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
  /** false when the device cannot report On-Air (XDJ-AZ); onAir is then always false */
  onAirKnown: boolean
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

/** Clojure の式のフックの実行状況 (server/src/main/java/plmux/ExpressionService.java) */
export interface ExpressionHook {
  name: string
  defined: boolean
  /** every の間隔 */
  intervalMs: number | null
  runs: number
  errors: number
  lastError: string | null
  lastErrorAt: number | null
}

export interface ExpressionsStatus {
  /** 式のファイルの場所 (サーバー上) */
  path: string
  exists: boolean
  source: string | null
  loadedAt: number | null
  /** 読み込めなかったときの理由 */
  error: string | null
  hooks: ExpressionHook[]
}

export type WaveformStyle = 'BLUE' | 'RGB' | 'THREE_BAND'

export interface Settings {
  waveformStyle: WaveformStyle
  waveformStyles: WaveformStyle[]
  linkStartupMode: LinkMode
  overlay: OverlaySettings
}

export type OverlayPosition = 'TOP_LEFT' | 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTTOM_RIGHT'

/** OBS 用オーバーレイ (/overlay) の見た目 (server/src/main/java/plmux/OverlaySettings.java) */
export interface OverlaySettings {
  theme: 'DARK' | 'LIGHT'
  position: OverlayPosition
  showArtwork: boolean
  hideWhenIdle: boolean
  /** カードの背景の不透明度 (0〜100)。0 で完全に透過 */
  backgroundOpacity: number
  tags: OverlayTag[]
}

export type OverlayTag = 'ALBUM' | 'GENRE' | 'KEY' | 'BPM'

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
  /** rekordbox のフレーズ解析 (曲の構成)。なければ空 */
  phrases: Phrase[]
}

/** 曲の構成の 1 区間 (server/src/main/java/plmux/Model.java の Phrase) */
export interface Phrase {
  /** 始まりの拍 (1 始まり) */
  beat: number
  /** 終わりの拍 (この拍は含まない) */
  endBeat: number
  kind: 'intro' | 'verse' | 'bridge' | 'chorus' | 'up' | 'down' | 'outro' | null
  label: string
  mood: 'LOW' | 'MID' | 'HIGH' | null
  color: string | null
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
