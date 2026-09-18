import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  server: {
    port: 7000,
    // 显式绑 IPv4：Node 17+ 把 localhost 优先解析成 ::1，Vite 默认只监听 IPv6 回环，
    // 而本机不少工具（curl、.NET 的 HttpClient）连不上 ::1，表现为「端口在监听却连不通」
    host: '127.0.0.1',
  },
  plugins: [vue(), tailwindcss(), vueDevTools()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
})
