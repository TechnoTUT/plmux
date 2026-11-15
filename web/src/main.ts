import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
import { installFlexGapFallback } from './legacy/flexGap'

createApp(App).mount('#app')
// iOS 12 などの古いブラウザ向け (対応していれば何もしない)
installFlexGapFallback()
