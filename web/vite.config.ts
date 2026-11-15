import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 開発時は `npm run dev` で Vite を起動し、API / WebSocket はローカルのサーバー (8080) に中継する。
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
})
