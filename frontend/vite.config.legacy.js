import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// JavaFX 21's WebView does not execute `<script type="module">`, so the desktop shell gets a separate IIFE build.
// The browser build in vite.config.js stays modern.
export default defineConfig({
  plugins: [vue()],
  // The IIFE bundle runs outside a Node/bundler process; Vue's runtime still references process.env.NODE_ENV.
  define: { 'process.env.NODE_ENV': '"production"', 'process.env': '{}' },
  build: {
    outDir: 'dist-legacy',
    emptyOutDir: true,
    target: 'es2015',
    cssCodeSplit: false,
    lib: {
      entry: 'src/legacy.js',
      name: 'OpenDavki',
      formats: ['iife'],
      fileName: () => 'app.js'
    },
    rollupOptions: {
      output: {
        assetFileNames: (asset) => (asset.name && asset.name.endsWith('.css') ? 'app.css' : 'assets/[name][extname]')
      }
    }
  }
})
