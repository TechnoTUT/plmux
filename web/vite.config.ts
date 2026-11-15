import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 開発時は `npm run dev` で Vite を起動し、API / WebSocket はローカルのサーバー (8080) に中継する。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  build: {
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
