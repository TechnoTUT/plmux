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
