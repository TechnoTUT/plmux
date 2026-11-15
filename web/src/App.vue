<script setup lang="ts">
import { computed } from 'vue'
import { useLive } from './composables/useLive'
import PlayerCard from './components/PlayerCard.vue'
import LinkPanel from './components/LinkPanel.vue'
import HistoryPanel from './components/HistoryPanel.vue'

const { state, connected } = useLive()

const statusLabel = computed(() => {
  if (!connected.value) return 'サーバーに再接続中…'
  if (!state.value?.djLinkOnline) return 'DJ Link 機器を探しています…'
  return 'DJ Link オンライン'
})
</script>

<template>
  <div class="app">
    <header class="top">
      <h1>plmux</h1>
      <span v-if="state?.demo" class="demo" title="PLMUX_DEMO=true: ダミーデータを表示中">DEMO</span>
      <span class="status" :class="{ ok: connected && state?.djLinkOnline }">
        <span class="dot"></span>{{ statusLabel }}
      </span>
      <div class="master" v-if="state?.masterPlayer">
        <span class="muted">MASTER</span>
        <strong class="num">{{ state.masterBpm.toFixed(1) }}</strong>
        <span class="muted">BPM · DECK {{ state.masterPlayer }}</span>
      </div>
    </header>

    <div v-for="w in state?.warnings ?? []" :key="w" class="warning">⚠ {{ w }}</div>

    <main>
      <section class="players">
        <PlayerCard v-for="p in state?.players ?? []" :key="p.number" :player="p" />
        <div v-if="state && state.players.length === 0" class="no-players panel muted">
          プレイヤーが見つかりません。XDJ-AZ / CDJ が同じネットワークにあり、Pro DJ Link (LINK) が有効か確認してください。
        </div>
      </section>

      <aside class="side">
        <LinkPanel :status="state?.link ?? null" />
        <HistoryPanel />
        <section v-if="state?.otherDevices.length" class="panel">
          <h2>その他の機器</h2>
          <ul class="devices">
            <li v-for="d in state.otherDevices" :key="d.number">
              <span class="num">#{{ d.number }}</span> {{ d.name }} <span class="muted">{{ d.address }}</span>
            </li>
          </ul>
        </section>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.app { max-width: 1440px; margin: 0 auto; padding: 16px; }
.top { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
h1 { margin: 0; font-size: 20px; font-weight: 800; letter-spacing: 0.02em; }
.demo {
  font-size: 11px; font-weight: 800; letter-spacing: 0.08em; padding: 2px 8px; border-radius: 4px;
  background: var(--sync); color: #14003a;
}
.status { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted); }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--master); }
.status.ok .dot { background: var(--play); }
.master { margin-left: auto; display: flex; align-items: baseline; gap: 6px; font-size: 13px; }
.master strong { font-size: 26px; color: var(--master); }
.warning {
  background: color-mix(in srgb, var(--master) 15%, var(--surface));
  border: 1px solid color-mix(in srgb, var(--master) 50%, var(--border));
  border-radius: var(--radius); padding: 10px 14px; margin-bottom: 12px; font-size: 14px;
}
main { display: grid; grid-template-columns: minmax(0, 1fr) 400px; gap: 16px; align-items: start; }
.players { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 12px; }
.no-players { grid-column: 1 / -1; text-align: center; padding: 40px 16px; }
.side { display: flex; flex-direction: column; gap: 16px; }
.devices { list-style: none; margin: 0; padding: 0; font-size: 14px; display: flex; flex-direction: column; gap: 4px; }
@media (max-width: 960px) {
  main { grid-template-columns: minmax(0, 1fr); }
  .players { grid-template-columns: minmax(0, 1fr); }
}
</style>
