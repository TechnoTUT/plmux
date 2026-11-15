/**
 * サーバーから 10Hz で届く再生位置を、描画フレームごとに滑らかに補間する。
 * 届いた位置と予測位置の差が小さければ少しずつ寄せ、大きければ (シーク・ロード) そのまま飛ぶ。
 */
export class Playhead {
  private pos = 0
  private at = 0
  private rate = 1
  private playing = false
  private valid = false

  update(positionMs: number, playing: boolean, rate: number, now = performance.now()) {
    if (positionMs < 0) {
      this.valid = false
      return
    }
    const predicted = this.positionAt(now)
    const snap = predicted === null || !playing || !this.playing || Math.abs(positionMs - predicted) > 250
    this.pos = snap ? positionMs : predicted + (positionMs - predicted) * 0.25
    this.at = now
    this.rate = rate > 0 ? rate : 1
    this.playing = playing
    this.valid = true
  }

  positionAt(now = performance.now()): number | null {
    if (!this.valid) return null
    return this.playing ? this.pos + (now - this.at) * this.rate : this.pos
  }
}

/** 波形 1 区間 (half frame) = 1/150 秒 */
export const FRAMES_PER_MS = 150 / 1000
