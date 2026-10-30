import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// The backend serves the built app (backend/src/main/resources/static); in development Vite
// forwards the API to it.
export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
  test: { environment: 'node' },
})
