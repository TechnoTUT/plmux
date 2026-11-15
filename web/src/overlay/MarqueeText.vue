<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * 枠に収まらないときだけ横にスクロールする 1 行テキスト。
 * 先頭で少し止まってから左へ流れ、2 つ目のコピーが先頭に来たところでループする (つなぎ目が見えない)。
 */
const props = withDefaults(defineProps<{ text: string; speed?: number; gap?: number; pauseSec?: number }>(), {
  speed: 60, // px/s
  gap: 64, // コピー同士の間隔 (px)
  pauseSec: 2.5, // 先頭で止まる時間
})

const box = ref<HTMLElement | null>(null)
const track = ref<HTMLElement | null>(null)
const content = ref<HTMLElement | null>(null)
const overflow = ref(false)
let anim: Animation | null = null
let lastDistance = 0

async function measure() {
  if (!box.value || !content.value) return
  const w = content.value.scrollWidth
  overflow.value = w > box.value.clientWidth + 1
  await nextTick() // 2 つ目のコピーを描画してからアニメーションを付ける
  const distance = overflow.value ? w + props.gap : 0
  if (distance === lastDistance && (anim || !distance)) return
  lastDistance = distance
  anim?.cancel()
  anim = null
  if (!distance || !track.value) return
  const moveMs = (distance / props.speed) * 1000
  const totalMs = moveMs + props.pauseSec * 1000
  anim = track.value.animate(
    [
      { transform: 'translateX(0)', offset: 0 },
      { transform: 'translateX(0)', offset: (props.pauseSec * 1000) / totalMs },
      { transform: `translateX(-${distance}px)`, offset: 1 },
    ],
    { duration: totalMs, iterations: Infinity, easing: 'linear' },
  )
}

// フォントの読み込みや枠の幅の変化にも追従する
const observer = new ResizeObserver(() => void measure())
onMounted(() => {
  if (box.value) observer.observe(box.value)
  if (content.value) observer.observe(content.value)
  void measure()
})
onBeforeUnmount(() => {
  observer.disconnect()
  anim?.cancel()
})
watch(() => props.text, () => nextTick(measure))
</script>

<template>
  <div ref="box" class="overflow-hidden whitespace-nowrap" :class="{ 'marquee-fade': overflow }">
    <div ref="track" class="inline-flex">
      <span ref="content">{{ text }}</span>
      <span v-if="overflow" :style="{ paddingLeft: `${gap}px` }" aria-hidden="true">{{ text }}</span>
    </div>
  </div>
</template>

<style scoped>
/* 流れている間は右端をフェードさせる (止まっているとき先頭の文字が欠けないよう左端はそのまま) */
.marquee-fade {
  mask-image: linear-gradient(to right, #000 calc(100% - 2.5rem), transparent 100%);
}
</style>
