<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useLive } from './composables/useLive'
import { useTheme } from './composables/useTheme'
import PlayerCard from './components/PlayerCard.vue'
import LinkPanel from './components/LinkPanel.vue'
import HistoryPanel from './components/HistoryPanel.vue'
import StatusPanel from './components/StatusPanel.vue'
import RecentTracks from './components/RecentTracks.vue'

const { state, connected } = useLive()
const { dark, toggle } = useTheme()

type Tab = 'decks' | 'history' | 'link'
const tabs: { id: Tab; label: string }[] = [
  { id: 'decks', label: 'Decks' },
  { id: 'history', label: 'History' },
  { id: 'link', label: 'Ableton Link' },
]
// タブは URL の #history のように指定でき、ブックマークやブース用ディスプレイで固定表示できる
function tabFromHash(): Tab {
  const h = location.hash.replace('#', '')
  return tabs.some((t) => t.id === h) ? (h as Tab) : 'decks'
}
const activeTab = ref<Tab>(tabFromHash())
watch(activeTab, (t) => history.replaceState(null, '', t === 'decks' ? location.pathname + location.search : `#${t}`))
window.addEventListener('hashchange', () => (activeTab.value = tabFromHash()))

const online = computed(() => connected.value && !!state.value?.djLinkOnline)
const anyPlaying = computed(() => state.value?.players.some((p) => p.playing && p.onAir) ?? false)
const link = computed(() => state.value?.link)

function tabDot(id: Tab): string {
  if (id === 'decks') return online.value ? (anyPlaying.value ? 'bg-emerald-400 animate-pulse' : 'bg-emerald-400') : 'bg-amber-400'
  if (id === 'link') {
    if (!link.value || link.value.mode === 'OFF') return 'bg-slate-300 dark:bg-slate-600'
    return link.value.connected ? 'bg-purple-400 animate-pulse' : 'bg-amber-400'
  }
  return 'bg-slate-300 dark:bg-slate-600'
}
</script>

<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-950 text-slate-800 dark:text-slate-100 flex flex-col font-sans transition-colors duration-200">
    <!-- Top Navigation Bar (moip-toolkit と同じ構成) -->
    <header class="border-b border-slate-200 dark:border-slate-800 bg-white/90 dark:bg-slate-900/80 backdrop-blur px-4 sm:px-6 py-3 flex flex-wrap items-center justify-between gap-3 sticky top-0 z-30 shadow-sm">
      <a href="/" class="flex items-center gap-4 py-0.5">
        <img src="/logo.svg" alt="TechnoTUT" class="h-10 sm:h-12 w-auto max-w-[200px] object-contain dark:hidden" />
        <img src="/logo_dark.svg" alt="TechnoTUT" class="h-10 sm:h-12 w-auto max-w-[200px] object-contain hidden dark:block" />
        <div class="h-8 w-px bg-slate-200 dark:bg-slate-800"></div>
        <div>
          <h1 class="text-base font-bold tracking-tight text-slate-900 dark:text-slate-100 flex items-center gap-2">
            <span>plmux</span>
            <span v-if="state?.demo" class="text-[10px] font-semibold text-purple-700 dark:text-purple-300 bg-purple-50 dark:bg-purple-950/40 px-2 py-0.5 rounded-full border border-purple-200 dark:border-purple-800" title="PLMUX_DEMO=true: ダミーデータを表示中">DEMO</span>
          </h1>
          <p class="text-[11px] text-slate-500 dark:text-slate-400">Pro DJ Link Monitor</p>
        </div>
      </a>

      <div class="flex flex-wrap items-center gap-3">
        <!-- Tab Switcher -->
        <nav class="flex bg-slate-100 dark:bg-slate-900 p-1 rounded-xl border border-slate-200 dark:border-slate-800">
          <button
            v-for="t in tabs"
            :key="t.id"
            @click="activeTab = t.id"
            :class="[
              'px-3 sm:px-5 py-2 rounded-lg text-sm font-semibold transition flex items-center gap-2',
              activeTab === t.id
                ? 'bg-brand text-white shadow-md shadow-brand/20'
                : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-100',
            ]"
          >
            <span>{{ t.label }}</span>
            <span class="h-2 w-2 rounded-full" :class="tabDot(t.id)" />
          </button>
        </nav>

        <!-- Live badges (固定幅 + tabular-nums でガタつき防止) -->
        <div class="hidden md:flex items-center gap-2 bg-slate-100 dark:bg-slate-900 px-3 py-1.5 rounded-xl border border-slate-200 dark:border-slate-800 text-xs font-mono select-none">
          <div class="flex items-center gap-1.5">
            <span class="text-slate-400 font-sans font-semibold text-[10px] uppercase">Master</span>
            <span class="w-[46px] text-right font-bold tabular-nums text-amber-500">
              {{ state?.masterPlayer ? state.masterBpm.toFixed(1) : '--.-' }}
            </span>
            <span class="text-slate-400 w-[14px]">{{ state?.masterPlayer ?? '' }}</span>
          </div>
          <div class="w-px h-3 bg-slate-300 dark:bg-slate-700"></div>
          <div class="flex items-center gap-1.5">
            <span class="text-slate-400 font-sans font-semibold text-[10px] uppercase">Link</span>
            <span class="w-[46px] text-right font-bold tabular-nums" :class="link?.connected ? 'text-purple-500' : 'text-slate-400'">
              {{ link?.connected ? link.bpm.toFixed(1) : '--.-' }}
            </span>
          </div>
          <div class="w-px h-3 bg-slate-300 dark:bg-slate-700"></div>
          <div class="flex items-center gap-1.5">
            <span class="h-2 w-2 rounded-full" :class="online ? 'bg-emerald-500' : connected ? 'bg-amber-400 animate-pulse' : 'bg-rose-500 animate-pulse'" />
            <span class="font-sans font-semibold text-[10px] uppercase" :class="online ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-500'">
              {{ online ? 'Online' : connected ? 'Searching' : 'Offline' }}
            </span>
          </div>
        </div>

        <!-- Theme Toggle -->
        <button
          @click="toggle"
          class="p-2 rounded-xl border border-slate-200 dark:border-slate-800 text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100 hover:bg-slate-100 dark:hover:bg-slate-800 transition"
          title="Toggle Light / Dark Mode"
        >
          <svg v-if="dark" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
          </svg>
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
          </svg>
        </button>
      </div>
    </header>

    <main class="flex-1 p-4 sm:p-6 max-w-7xl mx-auto w-full space-y-6">
      <!-- Warnings -->
      <div
        v-for="w in state?.warnings ?? []"
        :key="w"
        class="flex items-start gap-3 bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800/60 text-amber-800 dark:text-amber-300 rounded-2xl px-5 py-3 text-sm"
      >
        <span class="font-bold">!</span><span>{{ w }}</span>
      </div>
      <div v-if="!connected" class="bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/60 text-rose-700 dark:text-rose-400 rounded-2xl px-5 py-3 text-sm">
        サーバーとの接続が切れました。再接続しています…
      </div>

      <!-- ==================== TAB: Decks ==================== -->
      <div v-show="activeTab === 'decks'" class="grid grid-cols-1 lg:grid-cols-3 gap-6">
        <div class="lg:col-span-2 grid grid-cols-1 md:grid-cols-2 gap-6 content-start">
          <PlayerCard v-for="p in state?.players ?? []" :key="p.number" :player="p" />
          <div v-if="state && state.players.length === 0" class="card md:col-span-2 text-center py-16 space-y-2">
            <p class="font-semibold text-slate-700 dark:text-slate-200">プレイヤーが見つかりません</p>
            <p class="text-sm text-slate-500 dark:text-slate-400">
              XDJ-AZ / CDJ が同じネットワークにあり、Pro DJ Link (LINK) が有効か確認してください。
            </p>
          </div>
        </div>
        <div class="space-y-6">
          <StatusPanel :state="state" :connected="connected" />
          <RecentTracks @show-all="activeTab = 'history'" />
        </div>
      </div>

      <!-- ==================== TAB: History ==================== -->
      <HistoryPanel v-show="activeTab === 'history'" />

      <!-- ==================== TAB: Ableton Link ==================== -->
      <LinkPanel v-show="activeTab === 'link'" :status="state?.link ?? null" />
    </main>
  </div>
</template>
