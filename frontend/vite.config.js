import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Relative base so the built files work from whatever address `opendavki serve` picks.
export default defineConfig({
  base: './',
  plugins: [vue()],
  test: { environment: 'jsdom' }
})
