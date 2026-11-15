import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { legacyCss } from './build/legacyCss'

// 開発時は `npm run dev` で Vite を起動し、API / WebSocket はローカルのサーバー (8080) に中継する。
export default defineConfig({
  plugins: [vue(), tailwindcss(), legacyCss()],
  build: {
    // Decks タブを iOS 12 (Safari 12) でも表示できるようにする。
    // JS の構文と CSS の色・media query などは Vite が変換し、残りは legacyCss() で書き換える
    target: ['es2017', 'safari12'],
    cssTarget: ['safari12'],
    rollupOptions: {
      // GUI 本体と OBS 用オーバーレイ (/overlay) の 2 ページ
      input: { main: 'index.html', overlay: 'overlay.html' },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
