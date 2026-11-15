import { onMounted, onUnmounted, ref, shallowRef, type Ref } from 'vue'

/**
 * canvas の表示サイズに合わせて内部解像度 (devicePixelRatio 倍) を保つ。
 * size は device pixel 単位。
 */
export function useCanvas(canvas: Ref<HTMLCanvasElement | null>) {
  const ctx = shallowRef<CanvasRenderingContext2D | null>(null)
  const size = ref({ width: 1, height: 1, dpr: 1 })
  let observer: ResizeObserver | undefined
  let stopFallback: (() => void) | undefined

  function resize() {
    const el = canvas.value
    if (!el) return
    const dpr = window.devicePixelRatio || 1
    const width = Math.max(1, Math.round(el.clientWidth * dpr))
    const height = Math.max(1, Math.round(el.clientHeight * dpr))
    if (el.width !== width || el.height !== height) {
      el.width = width
      el.height = height
    }
    size.value = { width, height, dpr }
  }

  onMounted(() => {
    const el = canvas.value
    if (!el) return
    ctx.value = el.getContext('2d')
    resize()
    if (typeof ResizeObserver !== 'undefined') {
      observer = new ResizeObserver(resize)
      observer.observe(el)
    } else {
      // ResizeObserver がない古いブラウザ (Safari 13.1 未満、iOS 12 など) では画面サイズの変化だけ追う
      window.addEventListener('resize', resize)
      window.addEventListener('orientationchange', resize)
      stopFallback = () => {
        window.removeEventListener('resize', resize)
        window.removeEventListener('orientationchange', resize)
      }
    }
  })
  onUnmounted(() => {
    observer?.disconnect()
    stopFallback?.()
  })

  return { ctx, size }
}
