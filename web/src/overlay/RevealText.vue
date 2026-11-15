<script setup lang="ts">
import { computed } from 'vue'

/**
 * 左から 1 文字ずつフェード + スライドで現れるテキスト。マウントされるたびに再生する
 * (オーバーレイは曲ごとにカードを作り直すので、曲が変わるたびに再生される)。
 */
const props = withDefaults(defineProps<{ text: string; delay?: number; step?: number; maxDuration?: number }>(), {
  delay: 0, // 最初の文字が出始めるまで (秒)
  step: 0.035, // 1 文字ごとのずらし (秒)
  maxDuration: 1.2, // 長い文字列でも最後の文字がこの秒数までに出始めるよう step を縮める
})

// 日本語や絵文字の結合文字を壊さないよう書記素単位で分ける
const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' })
const chars = computed(() => Array.from(segmenter.segment(props.text), (s) => s.segment))
const stepSec = computed(() => Math.min(props.step, props.maxDuration / Math.max(chars.value.length, 1)))
</script>

<template>
  <span :aria-label="text"><span
    v-for="(c, i) in chars"
    :key="i"
    class="reveal-char"
    aria-hidden="true"
    :style="{ animationDelay: `${(delay + i * stepSec).toFixed(3)}s` }"
  >{{ c }}</span></span>
</template>

<style scoped>
.reveal-char {
  display: inline-block;
  white-space: pre; /* inline-block だと空白が潰れるので保持する */
  opacity: 0;
  animation: reveal 0.35s cubic-bezier(0.2, 0.7, 0.3, 1) forwards;
}
@keyframes reveal {
  from {
    opacity: 0;
    transform: translateY(0.35em);
    filter: blur(4px);
  }
  to {
    opacity: 1;
    transform: none;
    filter: none;
  }
}
</style>
