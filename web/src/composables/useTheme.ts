import { ref } from 'vue'

const dark = ref(document.documentElement.classList.contains('dark'))

export function useTheme() {
  function toggle() {
    dark.value = !dark.value
    document.documentElement.classList.toggle('dark', dark.value)
    try {
      localStorage.setItem('plmux-theme', dark.value ? 'dark' : 'light')
    } catch {
      // プライベートモード等で保存できなくても表示は切り替わる
    }
  }
  return { dark, toggle }
}
