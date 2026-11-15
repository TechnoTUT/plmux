import { onMounted, onUnmounted, ref, shallowRef, type Ref } from 'vue'

/**
 * canvas の表示サイズに合わせて内部解像度 (devicePixelRatio 倍) を保つ。
 * size は device pixel 単位。
 */
export function useCanvas(canvas: Ref<HTMLCanvasElement | null>) {
  const ctx = shallowRef<CanvasRenderingContext2D | null>(null)
  const size = ref({ width: 1, height: 1, dpr: 1 })
  let observer: ResizeObserver | undefined

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
    observer = new ResizeObserver(resize)
    observer.observe(el)
  })
  onUnmounted(() => observer?.disconnect())

  return { ctx, size }
}
