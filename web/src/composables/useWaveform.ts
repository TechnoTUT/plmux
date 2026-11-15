import { shallowRef, watch } from 'vue'
import { api } from '../api'
import type { Waveform, WaveformResponse } from '../types'

/** 波形データはキーごとに 1 回だけ取得・展開する (同じ曲を別デッキでロードしても共有) */
const cache = new Map<string, Promise<Waveform | null>>()

function decodeBase64(s: string): Uint8Array {
  const bin = atob(s)
  const out = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
  return out
}

function decode(r: WaveformResponse): Waveform {
  return {
    key: r.key,
    style: r.style,
    durationMs: r.durationMs,
    preview: r.preview && { ...r.preview, bytes: decodeBase64(r.preview.data) },
    detail: r.detail && { ...r.detail, bytes: decodeBase64(r.detail.data) },
    beatTimesMs: r.beatTimesMs,
    beatsWithinBar: r.beatsWithinBar,
    cues: r.cues,
  }
}

function load(player: number, key: string): Promise<Waveform | null> {
  let p = cache.get(key)
  if (!p) {
    p = api.getWaveform(player, key).then(decode).catch(() => {
      cache.delete(key)  // 失敗したら次のキー変化で再試行させる
      return null
    })
    cache.set(key, p)
    if (cache.size > 32) cache.delete(cache.keys().next().value!)
  }
  return p
}

/**
 * プレイヤーの waveformKey が変わるたびに波形を取得する。
 * 取得中は直前の波形を表示し続ける (プレビュー → 詳細と段階的に揃う場合のちらつき防止)。
 */
export function useWaveform(player: () => number, key: () => string | null | undefined) {
  const waveform = shallowRef<Waveform | null>(null)
  watch(
    [player, key],
    async ([p, k]) => {
      if (!k) {
        waveform.value = null
        return
      }
      const w = await load(p, k)
      if (key() === k) waveform.value = w
    },
    { immediate: true },
  )
  return waveform
}
