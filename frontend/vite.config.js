import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  // Artwork in public/ keeps its root URL, including during Windows SSR/tests.
  // Relative component assets still go through Vue's normal asset transform.
  plugins: [vue({template:{transformAssetUrls:{includeAbsolute:false}}})],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8090',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
})
